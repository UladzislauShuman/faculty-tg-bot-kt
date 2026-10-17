package shuman.bsu.famcs.rag.platform.notification

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/** Запасная реализация: пишет в журнал. Используется, пока адаптер Telegram выключен или администраторы не заданы. */
object LoggingAdminNotifier : AdminNotifier {
    override suspend fun notifyAdmins(text: String) {
        log.warn { "Оповещение администратору: $text" }
    }
}
