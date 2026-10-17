package shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation

import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.handler.command.CommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage

/**
 * Обработчик по умолчанию: команда не распознана. Используется, когда ни один другой обработчик команду не принял.
 *
 * @property sender отправка сообщений.
 * @property properties тексты бота.
 */
class UnknownCommandHandler(
    private val sender: ReplySender,
    private val properties: TelegramProperties,
) : CommandHandler {
    override fun supports(command: String) = false

    override suspend fun handle(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    ) {
        sender.send(message.chatId, properties.texts.unknownCommand)
    }
}
