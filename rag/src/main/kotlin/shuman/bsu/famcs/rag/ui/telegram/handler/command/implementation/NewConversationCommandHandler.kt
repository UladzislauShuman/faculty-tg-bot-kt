package shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation

import shuman.bsu.famcs.rag.api.ConversationService
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.handler.command.CommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage

/**
 * Команда `/new`: начать разговор заново, бот забывает прежний контекст.
 *
 * @property conversations управление диалогом.
 * @property sender отправка сообщений.
 * @property properties тексты бота.
 */
class NewConversationCommandHandler(
    private val conversations: ConversationService,
    private val sender: ReplySender,
    private val properties: TelegramProperties,
) : CommandHandler {
    override fun supports(command: String) = command == "new"

    override suspend fun handle(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    ) {
        conversations.reset(message.userId)
        sender.send(message.chatId, properties.texts.newSession)
    }
}
