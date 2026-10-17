package shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation

import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.handler.command.CommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.CallbackData
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.model.Button
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage

/**
 * Команда `/forget`: спрашивает подтверждение. Данные удаляются только после нажатия кнопки (см. `ForgetCallbackHandler`).
 *
 * @property sender отправка сообщений.
 * @property properties тексты бота.
 */
class ForgetCommandHandler(
    private val sender: ReplySender,
    private val properties: TelegramProperties,
) : CommandHandler {
    override fun supports(command: String) = command == "forget"

    override suspend fun handle(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    ) {
        sender.send(message.chatId, properties.texts.forgetConfirm, listOf(Button(properties.texts.forgetButton, CallbackData.FORGET)))
    }
}
