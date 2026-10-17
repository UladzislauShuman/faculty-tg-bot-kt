package shuman.bsu.famcs.rag.ui.telegram.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Оповещения администраторов личными сообщениями в Telegram.
 * Сбой доставки одному администратору не мешает остальным и не ломает вызывающий код: оповещение вторично.
 */
class TelegramAdminNotifier(
    private val gateway: TelegramGateway,
    private val adminIds: Set<Long>,
) : AdminNotifier {
    override suspend fun notifyAdmins(text: String) {
        adminIds.forEach { adminId ->
            try {
                gateway.sendText(ChatId(adminId), text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Оповещение администратору не доставлено: ${e.message}" }
            }
        }
    }
}
