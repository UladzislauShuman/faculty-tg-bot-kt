package shuman.bsu.famcs.rag.ui.telegram.handler.callback.implementation

import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.handler.callback.CallbackHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.CallbackData
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingCallback

/**
 * Кнопка подтверждения удаления данных: удаляет данные пользователя и сообщает об этом.
 *
 * @property users сервис пользователей.
 * @property gateway ответы на нажатия и удаление кнопок.
 * @property sender отправка сообщений.
 * @property properties тексты бота.
 */
class ForgetCallbackHandler(
    private val users: UserService,
    private val gateway: TelegramGateway,
    private val sender: ReplySender,
    private val properties: TelegramProperties,
) : CallbackHandler {
    override fun supports(data: String) = data == CallbackData.FORGET

    override suspend fun handle(callback: IncomingCallback) {
        users.forget(callback.userId)
        gateway.answerCallback(callback.callbackId)
        gateway.removeButtons(callback.chatId, callback.messageId)
        sender.send(callback.chatId, properties.texts.forgotten)
    }
}
