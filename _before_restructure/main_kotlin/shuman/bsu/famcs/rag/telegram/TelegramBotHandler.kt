package shuman.bsu.famcs.rag.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import shuman.bsu.famcs.rag.admin.AdminCommands
import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.ChatId
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.Templates
import shuman.bsu.famcs.rag.core.BotCore
import shuman.bsu.famcs.rag.core.CoreReply
import shuman.bsu.famcs.rag.core.CoreRequest
import shuman.bsu.famcs.rag.core.RejectionReason
import shuman.bsu.famcs.rag.dialog.ConsentProperties
import shuman.bsu.famcs.rag.dialog.ContextManager
import shuman.bsu.famcs.rag.dialog.UserDirectory
import shuman.bsu.famcs.rag.feedback.FeedbackService
import shuman.bsu.famcs.rag.feedback.RATING_RANGE
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * Логика бота в Telegram: команды, согласие, вопросы, кнопки оценки, удаление данных.
 *
 * Отвечает только за разговор с человеком. Как обрабатывается вопрос, решает ядро ([BotCore]); как доставляются сообщения —
 * библиотека за [TelegramGateway]. Поэтому этот класс проверяется тестами без сети.
 */
class TelegramBotHandler(
    private val core: BotCore,
    private val users: UserDirectory,
    private val contexts: ContextManager,
    private val feedback: FeedbackService,
    private val admin: AdminCommands,
    private val gateway: TelegramGateway,
    private val consent: ConsentProperties,
    private val properties: TelegramProperties,
    private val clock: Clock,
) {
    private val texts = properties.texts

    // --- Входящие сообщения ---

    suspend fun onMessage(message: IncomingMessage) {
        val text = message.text
        if (text != null && text.startsWith("/")) {
            handleCommand(message, text)
            return
        }
        handleQuestion(message)
    }

    private suspend fun handleCommand(
        message: IncomingMessage,
        text: String,
    ) {
        // «/start@my_bot аргумент» → команда «start», аргументы [«аргумент»]
        val parts = text.trim().split(Regex("\\s+"))
        val command = parts
            .first()
            .removePrefix("/")
            .substringBefore('@')
            .lowercase()
        val args = parts.drop(1)

        when {
            command == "start" -> {
                sendStart(message)
            }

            command == "help" -> {
                reply(message.chatId, texts.help)
            }

            command == "new" -> {
                contexts.reset(message.userId)
                reply(message.chatId, texts.newSession)
            }

            command == "forget" -> {
                reply(message.chatId, texts.forgetConfirm, listOf(Button(texts.forgetButton, FORGET)))
            }

            command.startsWith(ADMIN_PREFIX) -> {
                handleAdmin(message, command.removePrefix(ADMIN_PREFIX), args)
            }

            else -> {
                reply(message.chatId, texts.unknownCommand)
            }
        }
    }

    /** Новому пользователю — текст согласия с кнопкой, согласившемуся — приветствие. */
    private suspend fun sendStart(message: IncomingMessage) {
        val record = users.visit(message.userId, clock.instant())
        if (record.hasConsent(consent.version)) reply(message.chatId, texts.welcome) else sendConsentRequest(message.chatId)
    }

    private suspend fun sendConsentRequest(chatId: ChatId) =
        reply(chatId, consent.text, listOf(Button(consent.acceptButton, CONSENT_ACCEPT)))

    private suspend fun handleAdmin(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    ) {
        if (message.userId.value !in properties.adminIds) {
            reply(message.chatId, texts.notAdmin)
            return
        }
        reply(message.chatId, admin.execute(message.userId, command, args))
    }

    private suspend fun handleQuestion(message: IncomingMessage) {
        val request = CoreRequest(
            userId = message.userId,
            chatId = message.chatId,
            updateId = message.updateId,
            text = message.text,
            sentAt = message.sentAt,
        )
        val reply = withTyping(message.chatId) { core.ask(request) }
        if (!reply.deliverable) return
        if (reply.rejection == RejectionReason.CONSENT_REQUIRED) {
            sendConsentRequest(message.chatId)
            return
        }
        deliver(message.chatId, reply)
    }

    /** Отправляет ответ ядра. Кнопки оценки прикрепляются к последней части длинного ответа. */
    private suspend fun deliver(
        chatId: ChatId,
        reply: CoreReply,
    ) {
        val parts = TextSplitter.split(reply.text, properties.messageMaxChars)
        parts.forEachIndexed { index, part ->
            val buttons = if (index == parts.lastIndex) ratingButtons(reply.answerId) else emptyList()
            reply(chatId, part, buttons)
        }
    }

    private fun ratingButtons(answerId: AnswerId?): List<Button> = answerId
        ?.let { id ->
            RATING_RANGE.map { Button(Templates.fill(properties.ratingLabel, "n" to it.toString()), "$RATE${id.value}:$it") }
        }.orEmpty()

    // --- Нажатия кнопок ---

    suspend fun onCallback(callback: IncomingCallback) {
        when {
            callback.data == CONSENT_ACCEPT -> {
                acceptConsent(callback)
            }

            callback.data == FORGET -> {
                forget(callback)
            }

            callback.data.startsWith(RATE) -> {
                rate(callback)
            }

            else -> {
                log.warn { "Неизвестные данные кнопки" }
                gateway.answerCallback(callback.callbackId)
            }
        }
    }

    private suspend fun acceptConsent(callback: IncomingCallback) {
        users.visit(callback.userId, clock.instant())
        users.grantConsent(callback.userId, consent.version, clock.instant())
        gateway.answerCallback(callback.callbackId, texts.consentAccepted)
        gateway.removeButtons(callback.chatId, callback.messageId)
        reply(callback.chatId, texts.welcome)
    }

    private suspend fun forget(callback: IncomingCallback) {
        users.forget(callback.userId)
        gateway.answerCallback(callback.callbackId)
        gateway.removeButtons(callback.chatId, callback.messageId)
        reply(callback.chatId, texts.forgotten)
    }

    /** Данные кнопки оценки: `rate:<номер ответа>:<оценка>`. Подделанные данные игнорируются. */
    private suspend fun rate(callback: IncomingCallback) {
        val fields = callback.data.removePrefix(RATE).split(':')
        val answerId = fields.getOrNull(0)?.toLongOrNull()
        val rating = fields.getOrNull(1)?.toIntOrNull()
        if (fields.size != 2 || answerId == null || rating == null || rating !in RATING_RANGE) {
            gateway.answerCallback(callback.callbackId)
            return
        }
        val saved = feedback.rate(AnswerId(answerId), callback.userId, rating)
        gateway.answerCallback(callback.callbackId, if (saved) texts.ratingThanks else null)
        if (saved) gateway.removeButtons(callback.chatId, callback.messageId)
    }

    // --- Отправка ---

    /** Отправляет текст; если человек заблокировал бота, помечает его неактивным вместо ошибки. */
    private suspend fun reply(
        chatId: ChatId,
        text: String,
        buttons: List<Button> = emptyList(),
    ) {
        try {
            gateway.sendText(chatId, text, buttons)
        } catch (e: UserBlockedBotException) {
            log.info { "Пользователь заблокировал бота: ответ не отправлен" }
            users.markBlocked(TelegramId(chatId.value))
        }
    }

    /** Пока идёт [block], поддерживает индикатор «печатает…». Сбой индикатора на ответ не влияет. */
    private suspend fun <T> withTyping(
        chatId: ChatId,
        block: suspend () -> T,
    ): T = coroutineScope {
        val indicator = launch {
            // Мгновенные отказы укладываются в паузу и индикатора не вызывают.
            delay(properties.typingStartDelay.toKotlinDuration())
            while (true) {
                try {
                    gateway.showTyping(chatId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.debug { "Индикатор «печатает» не показан: ${e.message}" }
                }
                delay(properties.typingInterval.toKotlinDuration())
            }
        }
        try {
            block()
        } finally {
            indicator.cancel()
        }
    }

    private companion object {
        const val CONSENT_ACCEPT = "consent:accept"
        const val FORGET = "forget:confirm"
        const val RATE = "rate:"
        const val ADMIN_PREFIX = "admin_"
    }
}
