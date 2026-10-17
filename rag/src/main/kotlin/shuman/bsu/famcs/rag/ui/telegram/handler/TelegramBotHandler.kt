package shuman.bsu.famcs.rag.ui.telegram.handler

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.handler.callback.CallbackHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.command.CommandHandler
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingCallback
import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage

private val log = KotlinLogging.logger {}

/**
 * Маршрутизатор входящих событий Telegram: точка входа для чтения кода бота.
 *
 * Сообщение, начинающееся с `/`, идёт к обработчику команды, остальное — в [QuestionHandler]; нажатие кнопки идёт к
 * обработчику, который узнал её данные. Сама логика команд и кнопок лежит в `command/` и `callback/`.
 * Класс проверяется тестами без сети: Telegram скрыт за [TelegramGateway].
 *
 * @property commands обработчики команд.
 * @property unknownCommand обработчик для нераспознанной команды.
 * @property question обработчик обычных сообщений.
 * @property callbacks обработчики нажатий кнопок.
 * @property gateway ответы на неизвестные нажатия.
 */
class TelegramBotHandler(
    private val commands: List<CommandHandler>,
    private val unknownCommand: CommandHandler,
    private val question: QuestionHandler,
    private val callbacks: List<CallbackHandler>,
    private val gateway: TelegramGateway,
) {
    /**
     * Точка входа для входящего сообщения: команды (начинаются с `/`) идут к обработчику команды, остальное — вопрос системе.
     *
     * @param message входящее сообщение.
     */
    suspend fun onMessage(message: IncomingMessage) {
        val text = message.text
        if (text == null || !text.startsWith("/")) {
            question.handle(message)
            return
        }
        // «/start@my_bot аргумент» → команда «start», аргументы [«аргумент»]
        val parts = text.trim().split(Regex("\\s+"))
        val command = parts
            .first()
            .removePrefix("/")
            .substringBefore('@')
            .lowercase()
        val handler = commands.firstOrNull { it.supports(command) } ?: unknownCommand
        handler.handle(message, command, parts.drop(1))
    }

    /**
     * Точка входа для нажатия кнопки: согласие, «забыть меня» или оценка ответа. Неизвестные данные кнопки игнорируются.
     *
     * @param callback нажатие кнопки.
     */
    suspend fun onCallback(callback: IncomingCallback) {
        val handler = callbacks.firstOrNull { it.supports(callback.data) }
        if (handler == null) {
            log.warn { "Неизвестные данные кнопки" }
            gateway.answerCallback(callback.callbackId)
            return
        }
        handler.handle(callback)
    }
}
