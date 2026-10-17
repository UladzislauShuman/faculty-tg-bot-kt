package shuman.bsu.famcs.rag.ui.telegram.handler.callback.implementation

import shuman.bsu.famcs.rag.api.FeedbackService
import shuman.bsu.famcs.rag.api.model.RATING_RANGE
import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.handler.callback.CallbackHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.CallbackData
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingCallback

/**
 * Кнопки оценки ответа. Данные кнопки: `rate:<номер ответа>:<оценка>`; подделанные данные игнорируются.
 *
 * @property feedback сервис оценок.
 * @property gateway ответы на нажатия и удаление кнопок.
 * @property properties тексты бота.
 */
class RatingCallbackHandler(
    private val feedback: FeedbackService,
    private val gateway: TelegramGateway,
    private val properties: TelegramProperties,
) : CallbackHandler {
    override fun supports(data: String) = data.startsWith(CallbackData.RATE)

    override suspend fun handle(callback: IncomingCallback) {
        val fields = callback.data.removePrefix(CallbackData.RATE).split(':')
        val answerId = fields.getOrNull(0)?.toLongOrNull()
        val rating = fields.getOrNull(1)?.toIntOrNull()
        if (fields.size != 2 || answerId == null || rating == null || rating !in RATING_RANGE) {
            gateway.answerCallback(callback.callbackId)
            return
        }
        val saved = feedback.rate(AnswerId(answerId), callback.userId, rating)
        gateway.answerCallback(callback.callbackId, if (saved) properties.texts.ratingThanks else null)
        if (saved) gateway.removeButtons(callback.chatId, callback.messageId)
    }
}
