package shuman.bsu.famcs.rag.ui.telegram.handler.callback.implementation

import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.handler.callback.CallbackHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.CallbackData
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingCallback
import java.time.Clock

/**
 * Кнопка «Согласен»: записывает согласие, убирает кнопку и отправляет приветствие.
 *
 * @property users сервис пользователей.
 * @property consent версия согласия.
 * @property gateway ответы на нажатия и удаление кнопок.
 * @property sender отправка сообщений.
 * @property properties тексты бота.
 * @property clock часы.
 */
class ConsentCallbackHandler(
    private val users: UserService,
    private val consent: ConsentProperties,
    private val gateway: TelegramGateway,
    private val sender: ReplySender,
    private val properties: TelegramProperties,
    private val clock: Clock,
) : CallbackHandler {
    override fun supports(data: String) = data == CallbackData.CONSENT_ACCEPT

    override suspend fun handle(callback: IncomingCallback) {
        users.visit(callback.userId, clock.instant())
        users.grantConsent(callback.userId, consent.version, clock.instant())
        gateway.answerCallback(callback.callbackId, properties.texts.consentAccepted)
        gateway.removeButtons(callback.chatId, callback.messageId)
        sender.send(callback.chatId, properties.texts.welcome)
    }
}
