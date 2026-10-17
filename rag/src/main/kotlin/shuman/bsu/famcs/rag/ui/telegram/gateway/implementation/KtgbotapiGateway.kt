package shuman.bsu.famcs.rag.ui.telegram.gateway.implementation

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.types.CallbackQueryId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.ui.telegram.exception.TelegramSendException
import shuman.bsu.famcs.rag.ui.telegram.exception.UserBlockedBotException
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.model.Button
import shuman.bsu.famcs.rag.ui.telegram.security.TokenRedaction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import dev.inmo.tgbotapi.types.ChatId as TgChatId

private val log = KotlinLogging.logger {}

/**
 * Отправка сообщений через библиотеку ktgbotapi.
 *
 * Здесь же перевод ошибок Telegram в понятные логике: «слишком часто» (429) — ждать указанное время и повторить,
 * «запрещено» (403) — [UserBlockedBotException], остальное — [TelegramSendException]. Исходные исключения библиотеки дальше
 * не уходят: их тексты содержат адрес запроса с токеном бота, наши исключения несут только очищенный текст ([TokenRedaction]).
 * Тексты отправляются без разметки: Markdown-экранирование ответов модели ломало бы сообщения случайными символами.
 */
class KtgbotapiGateway(
    private val bot: TelegramBot,
    private val retries: Int,
) : TelegramGateway {
    override suspend fun sendText(
        chatId: ChatId,
        text: String,
        buttons: List<Button>,
    ): Long = withRetries {
        val message = bot.sendTextMessage(chatId.toTelegram(), text, replyMarkup = buttons.toMarkup())
        message.messageId.long
    }

    override suspend fun showTyping(chatId: ChatId) {
        quietly("индикатор «печатает»") { bot.sendActionTyping(chatId.toTelegram()) }
    }

    override suspend fun answerCallback(
        callbackId: String,
        text: String?,
    ) {
        // Ответ на устаревшее нажатие (после простоя Telegram отдаёт старые) не должен обрывать обработку: действие уже выполнено.
        quietly("ответ на нажатие кнопки") { bot.answerCallbackQuery(CallbackQueryId(callbackId), text) }
    }

    override suspend fun removeButtons(
        chatId: ChatId,
        messageId: Long,
    ) {
        // Кнопки могли уже исчезнуть (двойное нажатие) или сообщение удалено: пользователю это не мешает.
        quietly("удаление кнопок") { bot.editMessageReplyMarkup(chatId.toTelegram(), MessageId(messageId), replyMarkup = null) }
    }

    /** Выполняет вызов; при ответе 429 ждёт время, названное Telegram, и повторяет не больше [retries] раз. */
    private suspend fun <T> withRetries(call: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooMuchRequestsException) {
                if (attempt++ >= retries) throw TelegramSendException("Telegram ограничил частоту отправки")
                val pause = e.retryAfter.seconds
                    .coerceAtLeast(1)
                    .seconds
                log.warn { "Telegram просит подождать $pause" }
                delay(pause)
            } catch (e: Exception) {
                throw translate(e)
            }
        }
    }

    /** Вызов, сбой которого не критичен: фиксируется на уровне отладки, без токена и трассировки. */
    private suspend fun quietly(
        what: String,
        call: suspend () -> Unit,
    ) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug { "$what не выполнено: ${TokenRedaction.describe(e)}" }
        }
    }

    /**
     * Переводит исключение библиотеки в исключение, понятное логике бота. Исходный текст ошибки наружу не выходит:
     * в нём может быть адрес запроса с токеном бота.
     *
     * @param e исключение библиотеки.
     * @return [UserBlockedBotException] при коде 403, иначе [TelegramSendException] с очищенным текстом.
     */
    internal fun translate(e: Exception): Exception = when {
        e is RequestException && e.response.errorCode == HTTP_FORBIDDEN -> UserBlockedBotException(
            "Telegram запретил писать пользователю (403)",
        )

        else -> TelegramSendException("Не удалось отправить сообщение: ${TokenRedaction.describe(e)}")
    }

    /** Переводит наш идентификатор чата в тип библиотеки. */
    private fun ChatId.toTelegram() = TgChatId(RawChatId(value))

    /** Строит инлайн-клавиатуру из кнопок (все в один ряд); без кнопок разметка не нужна. */
    private fun List<Button>.toMarkup(): InlineKeyboardMarkup? =
        takeIf { it.isNotEmpty() }?.let { list ->
            InlineKeyboardMarkup(listOf(list.map { CallbackDataInlineKeyboardButton(it.text, it.data) }))
        }
}

/** Код ответа Bot API «запрещено»: пользователь заблокировал бота или удалил аккаунт. */
private const val HTTP_FORBIDDEN = 403
