package shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation

import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.handler.command.CommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ConsentPrompt
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage
import java.time.Clock

/**
 * Команда `/start`: новому пользователю показывает текст согласия, согласившемуся — приветствие.
 *
 * @property users сервис пользователей.
 * @property consent версия согласия.
 * @property consentPrompt запрос согласия.
 * @property sender отправка сообщений.
 * @property properties тексты бота.
 * @property clock часы.
 */
class StartCommandHandler(
    private val users: UserService,
    private val consent: ConsentProperties,
    private val consentPrompt: ConsentPrompt,
    private val sender: ReplySender,
    private val properties: TelegramProperties,
    private val clock: Clock,
) : CommandHandler {
    override fun supports(command: String) = command == "start"

    override suspend fun handle(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    ) {
        val record = users.visit(message.userId, clock.instant())
        if (record.hasConsent(consent.version)) {
            sender.send(message.chatId, properties.texts.welcome)
        } else {
            consentPrompt.show(message.chatId)
        }
    }
}
