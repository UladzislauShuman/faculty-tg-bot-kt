package shuman.bsu.famcs.rag.ui.telegram.handler

import shuman.bsu.famcs.rag.api.AdminService
import shuman.bsu.famcs.rag.api.ConversationService
import shuman.bsu.famcs.rag.api.FeedbackService
import shuman.bsu.famcs.rag.api.RagService
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.handler.callback.implementation.ConsentCallbackHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.callback.implementation.ForgetCallbackHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.callback.implementation.RatingCallbackHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation.AdminCommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation.ForgetCommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation.HelpCommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation.NewConversationCommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation.StartCommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.command.implementation.UnknownCommandHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ConsentPrompt
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.ReplySender
import shuman.bsu.famcs.rag.ui.telegram.handler.reply.TypingIndicator
import java.time.Clock

/**
 * Единственное место, где обработчики команд и кнопок собираются в [TelegramBotHandler].
 * Новая команда или кнопка добавляется сюда одной строкой.
 */
object TelegramHandlerFactory {
    /**
     * Собирает маршрутизатор со всеми обработчиками.
     *
     * @param core вход в систему.
     * @param users сервис пользователей.
     * @param conversations управление диалогом.
     * @param feedback сервис оценок.
     * @param admin административные команды.
     * @param gateway отправка через Telegram.
     * @param consent текст и версия согласия.
     * @param properties настройки адаптера.
     * @param clock часы.
     */
    fun create(
        core: RagService,
        users: UserService,
        conversations: ConversationService,
        feedback: FeedbackService,
        admin: AdminService,
        gateway: TelegramGateway,
        consent: ConsentProperties,
        properties: TelegramProperties,
        clock: Clock,
    ): TelegramBotHandler {
        val sender = ReplySender(gateway, users)
        val consentPrompt = ConsentPrompt(sender, consent)
        return TelegramBotHandler(
            commands = listOf(
                StartCommandHandler(users, consent, consentPrompt, sender, properties, clock),
                HelpCommandHandler(sender, properties),
                NewConversationCommandHandler(conversations, sender, properties),
                ForgetCommandHandler(sender, properties),
                AdminCommandHandler(admin, sender, properties),
            ),
            unknownCommand = UnknownCommandHandler(sender, properties),
            question = QuestionHandler(core, sender, TypingIndicator(gateway, properties), consentPrompt, properties),
            callbacks = listOf(
                ConsentCallbackHandler(users, consent, gateway, sender, properties, clock),
                ForgetCallbackHandler(users, gateway, sender, properties),
                RatingCallbackHandler(feedback, gateway, properties),
            ),
            gateway = gateway,
        )
    }
}
