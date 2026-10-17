package shuman.bsu.famcs.rag.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.utils.textContentOrNull
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.types.update.CallbackQueryUpdate
import dev.inmo.tgbotapi.types.update.MessageUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.context.SmartLifecycle
import shuman.bsu.famcs.rag.app.ApplicationScope
import shuman.bsu.famcs.rag.common.ChatId
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UpdateId
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Приём обновлений Telegram длинным опросом (бот сам забирает сообщения, входящий порт не нужен).
 *
 * Каждое обновление обрабатывается в своей корутине ([UpdateDispatcher]), чтобы долгий ответ модели не задерживал приём следующих.
 * Число одновременно работающих вопросов ограничивает ядро (очередь и лимиты), число самих обработчиков — диспетчер.
 * Работает под управлением Spring: стартует после сборки всех бинов; при остановке сначала перестаёт принимать сообщения,
 * затем дожидается уже принятых и только потом отдаёт управление закрытию базы.
 */
class TelegramPoller(
    private val bot: TelegramBot,
    private val handler: TelegramBotHandler,
    private val scope: ApplicationScope,
    private val dispatcher: UpdateDispatcher,
    private val pollTimeoutSeconds: Int,
) : SmartLifecycle {
    @Volatile
    private var polling: Job? = null

    override fun start() {
        polling = scope.launch(CoroutineName("telegram-polling")) {
            bot
                .buildBehaviourWithLongPolling(
                    scope = this,
                    timeoutSeconds = pollTimeoutSeconds,
                    // Исключения сети приводят адрес запроса с токеном бота: в журнал идёт только очищенное описание, без трассировки.
                    defaultExceptionsHandler = { error ->
                        log.error { "Ошибка при приёме обновлений Telegram: ${TokenRedaction.describe(error)}" }
                    },
                ) {
                    launch { messagesFlow.collect { update -> dispatcher.submit("message") { dispatchMessage(update) } } }
                    launch { callbackQueriesFlow.collect { update -> dispatcher.submit("callback") { dispatchCallback(update) } } }
                }.join()
        }
        log.info { "Приём обновлений Telegram запущен" }
    }

    override fun stop() {
        polling?.cancel()
        runBlocking { dispatcher.drain() }
        log.info { "Приём обновлений Telegram остановлен" }
    }

    override fun isRunning() = polling?.isActive == true

    /** Останавливается раньше базы данных и пула соединений, чтобы не принимать новые сообщения при закрытии. */
    override fun getPhase() = Int.MAX_VALUE

    private suspend fun dispatchMessage(update: MessageUpdate) {
        val message = update.data as? ContentMessage<*> ?: return
        val chat = message.chat as? PrivateChat ?: return
        val incoming = IncomingMessage(
            updateId = UpdateId(update.updateId.long),
            userId = TelegramId(chat.id.chatId.long),
            chatId = ChatId(chat.id.chatId.long),
            text = message.content.textContentOrNull()?.text,
            sentAt = Instant.ofEpochMilli(message.date.unixMillisLong),
        )
        guarded { handler.onMessage(incoming) }
    }

    private suspend fun dispatchCallback(update: CallbackQueryUpdate) {
        val query = update.data as? MessageDataCallbackQuery ?: return
        val message = query.message
        val incoming = IncomingCallback(
            updateId = UpdateId(update.updateId.long),
            callbackId = query.id.string,
            userId = TelegramId(query.user.id.chatId.long),
            chatId = ChatId(message.chat.id.chatId.long),
            messageId = message.messageId.long,
            data = query.data,
        )
        guarded { handler.onCallback(incoming) }
    }

    /** Сбой одного обновления не должен ронять приём остальных. Ошибки Telegram приходят из шлюза уже очищенными от токена. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Не удалось обработать обновление Telegram" }
        }
    }
}
