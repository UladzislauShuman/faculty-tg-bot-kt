package shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation

import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.handler.command.CommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage

/**
 * Команда `/help`: справка по боту.
 *
 * @property sender отправка сообщений.
 * @property properties тексты бота.
 */
class HelpCommandHandler(
    private val sender: ReplySender,
    private val properties: TelegramProperties,
) : CommandHandler {
    override fun supports(command: String) = command == "help"

    override suspend fun handle(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    ) {
        sender.send(message.chatId, properties.texts.help)
    }
}
