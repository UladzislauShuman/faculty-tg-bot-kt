package shuman.bsu.famcs.rag.ui.telegram.handler

import shuman.bsu.famcs.rag.api.RagService
import shuman.bsu.famcs.rag.api.model.RATING_RANGE
import shuman.bsu.famcs.rag.api.model.RagReply
import shuman.bsu.famcs.rag.api.model.RagRequest
import shuman.bsu.famcs.rag.api.model.RejectionReason
import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.text.Templates
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.CallbackData
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ConsentPrompt
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.TypingIndicator
import shuman.bsu.famcs.rag.ui.telegram.model.Button
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage
import shuman.bsu.famcs.rag.ui.telegram.util.TextSplitter

/**
 * Обработка обычного сообщения (не команды): вопрос уходит в систему, ответ возвращается пользователю с кнопками оценки.
 *
 * @property core вход в систему.
 * @property sender отправка сообщений.
 * @property typing индикатор «печатает…».
 * @property consentPrompt запрос согласия, если оно не получено.
 * @property properties длина сообщения и подпись кнопок оценки.
 */
class QuestionHandler(
    private val core: RagService,
    private val sender: ReplySender,
    private val typing: TypingIndicator,
    private val consentPrompt: ConsentPrompt,
    private val properties: TelegramProperties,
) {
    /**
     * Передаёт вопрос системе (пока она думает, показывается «печатает…») и отправляет ответ.
     *
     * @param message сообщение пользователя.
     */
    suspend fun handle(message: IncomingMessage) {
        val request = RagRequest(
            userId = message.userId,
            chatId = message.chatId,
            updateId = message.updateId,
            text = message.text,
            sentAt = message.sentAt,
        )
        val reply = typing.during(message.chatId) { core.ask(request) }
        if (!reply.deliverable) return
        if (reply.rejection == RejectionReason.CONSENT_REQUIRED) {
            consentPrompt.show(message.chatId)
            return
        }
        deliver(message.chatId, reply)
    }

    /**
     * Отправляет ответ системы. Кнопки оценки прикрепляются к последней части длинного ответа.
     *
     * @param chatId чат пользователя.
     * @param reply ответ системы.
     */
    private suspend fun deliver(
        chatId: ChatId,
        reply: RagReply,
    ) {
        val parts = TextSplitter.split(reply.text, properties.messageMaxChars)
        parts.forEachIndexed { index, part ->
            val buttons = if (index == parts.lastIndex) ratingButtons(reply.answerId) else emptyList()
            sender.send(chatId, part, buttons)
        }
    }

    /**
     * Строит кнопки оценки 1–5 под ответом. В данных кнопки закодированы номер ответа и оценка.
     *
     * @param answerId номер записи журнала; без него (отказы, ошибки) оценивать нечего.
     */
    private fun ratingButtons(answerId: AnswerId?): List<Button> = answerId
        ?.let { id ->
            RATING_RANGE.map {
                Button(Templates.fill(properties.ratingLabel, "n" to it.toString()), "${CallbackData.RATE}${id.value}:$it")
            }
        }.orEmpty()
}
