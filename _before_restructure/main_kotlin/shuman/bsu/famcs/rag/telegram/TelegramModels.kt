package shuman.bsu.famcs.rag.telegram

import shuman.bsu.famcs.rag.common.ChatId
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UpdateId
import java.time.Instant

/*
 * Типы, на которых логика бота общается с Telegram.
 *
 * Логика (команды, согласие, оценки) не импортирует библиотеку Telegram: она получает [IncomingMessage] и [IncomingCallback]
 * и отправляет ответы через [TelegramGateway]. Библиотеку можно заменить, а логику проверить без сети.
 */

/** Входящее сообщение из личного диалога. */
data class IncomingMessage(
    val updateId: UpdateId,
    val userId: TelegramId,
    val chatId: ChatId,
    /** Текст. `null` — пришло не текстовое сообщение (фото, голос, файл). */
    val text: String?,
    val sentAt: Instant,
)

/** Нажатие кнопки под сообщением. */
data class IncomingCallback(
    val updateId: UpdateId,
    /** Идентификатор нажатия: на него нужно ответить, иначе у кнопки долго крутится индикатор. */
    val callbackId: String,
    val userId: TelegramId,
    val chatId: ChatId,
    /** Сообщение, под которым нажали кнопку. */
    val messageId: Long,
    /** Данные кнопки (до 64 байт): `consent:accept`, `rate:<ответ>:<оценка>`, `forget:confirm`. */
    val data: String,
)

/** Кнопка под сообщением. */
data class Button(
    val text: String,
    val data: String,
)

/** Пользователь заблокировал бота (Telegram ответил 403). Ответы ему больше не отправляются. */
class UserBlockedBotException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Отправка не удалась по иной причине (сеть, 5xx, исчерпаны повторы после 429). */
class TelegramSendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Всё, что логике нужно от Telegram. Реализация — на библиотеке ktgbotapi. */
interface TelegramGateway {
    /** Отправляет текст (не длиннее лимита Telegram) и необязательный ряд кнопок. Возвращает номер сообщения. */
    suspend fun sendText(
        chatId: ChatId,
        text: String,
        buttons: List<Button> = emptyList(),
    ): Long

    /** Показывает «печатает…». Ошибки не критичны и не пробрасываются. */
    suspend fun showTyping(chatId: ChatId)

    /** Отвечает на нажатие кнопки: гасит индикатор, при необходимости показывает короткое всплывающее [text]. */
    suspend fun answerCallback(
        callbackId: String,
        text: String? = null,
    )

    /** Убирает кнопки под сообщением (после оценки или согласия). */
    suspend fun removeButtons(
        chatId: ChatId,
        messageId: Long,
    )
}
