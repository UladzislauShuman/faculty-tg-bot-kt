package shuman.bsu.famcs.rag.ui.telegram.handler.reply

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * Индикатор «печатает…» на время долгой работы.
 *
 * @property gateway отправка через Telegram.
 * @property properties задержка перед первым показом и период обновления.
 */
class TypingIndicator(
    private val gateway: TelegramGateway,
    private val properties: TelegramProperties,
) {
    /**
     * Пока идёт [block], поддерживает индикатор. Мгновенные отказы укладываются в начальную паузу и индикатора не вызывают.
     * Сбой индикатора на ответ не влияет.
     *
     * @param chatId чат пользователя.
     * @param block работа, на время которой показывается индикатор.
     * @return результат [block].
     */
    suspend fun <T> during(
        chatId: ChatId,
        block: suspend () -> T,
    ): T = coroutineScope {
        val indicator = launch {
            delay(properties.typingStartDelay.toKotlinDuration())
            while (true) {
                try {
                    gateway.showTyping(chatId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.debug { "Индикатор «печатает» не показан: ${e.message}" }
                }
                delay(properties.typingInterval.toKotlinDuration())
            }
        }
        try {
            block()
        } finally {
            indicator.cancel()
        }
    }
}
