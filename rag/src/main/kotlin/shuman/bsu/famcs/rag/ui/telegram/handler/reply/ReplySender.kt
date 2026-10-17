package shuman.bsu.famcs.rag.ui.telegram.handler.reply

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.ui.telegram.exception.UserBlockedBotException
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.model.Button

private val log = KotlinLogging.logger {}

/**
 * Отправка сообщений пользователю. Общая часть всех обработчиков.
 *
 * @property gateway отправка через Telegram.
 * @property users сервис пользователей: сюда сообщается, что человек заблокировал бота.
 */
class ReplySender(
    private val gateway: TelegramGateway,
    private val users: UserService,
) {
    /**
     * Отправляет текст; если человек заблокировал бота, помечает его неактивным вместо ошибки.
     *
     * @param chatId чат получателя.
     * @param text текст сообщения (не длиннее лимита Telegram).
     * @param buttons кнопки под сообщением.
     */
    suspend fun send(
        chatId: ChatId,
        text: String,
        buttons: List<Button> = emptyList(),
    ) {
        try {
            gateway.sendText(chatId, text, buttons)
        } catch (e: UserBlockedBotException) {
            log.info { "Пользователь заблокировал бота: ответ не отправлен" }
            users.markBlocked(TelegramId(chatId.value))
        }
    }
}
