package shuman.bsu.famcs.rag.ui.telegram.gateway

import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.ui.telegram.model.Button

/**
 * Всё, что логике бота нужно от Telegram. Реализация — на библиотеке ktgbotapi.
 *
 * Логика (команды, согласие, оценки) не импортирует библиотеку Telegram: она получает [shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage]
 * и [shuman.bsu.famcs.rag.ui.telegram.model.IncomingCallback] и отправляет ответы через этот интерфейс. Библиотеку можно заменить,
 * а логику проверить без сети.
 */
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
