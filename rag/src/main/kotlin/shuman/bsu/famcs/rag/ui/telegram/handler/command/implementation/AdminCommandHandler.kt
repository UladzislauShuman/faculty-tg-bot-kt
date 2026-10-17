package shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation

import shuman.bsu.famcs.rag.api.AdminService
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.handler.command.CommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage

/**
 * Административные команды `/admin_*`. Выполняются только для администраторов из `bot.telegram.admin-ids`.
 *
 * @property admin сервис административных команд.
 * @property sender отправка сообщений.
 * @property properties список администраторов и тексты бота.
 */
class AdminCommandHandler(
    private val admin: AdminService,
    private val sender: ReplySender,
    private val properties: TelegramProperties,
) : CommandHandler {
    override fun supports(command: String) = command.startsWith(PREFIX)

    override suspend fun handle(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    ) {
        if (message.userId.value !in properties.adminIds) {
            sender.send(message.chatId, properties.texts.notAdmin)
            return
        }
        sender.send(message.chatId, admin.execute(message.userId, command.removePrefix(PREFIX), args))
    }

    private companion object {
        const val PREFIX = "admin_"
    }
}
