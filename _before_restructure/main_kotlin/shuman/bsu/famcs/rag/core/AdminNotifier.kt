package shuman.bsu.famcs.rag.core

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Оповещение администраторов: расход бюджета, сбои провайдеров, завершение индексации.
 * Реализация для Telegram живёт в адаптере; ядро знает только этот интерфейс.
 */
fun interface AdminNotifier {
    /** Отправляет сообщение всем администраторам. Сбой отправки не должен ломать вызывающий код. */
    suspend fun notifyAdmins(text: String)
}

/** Запасная реализация: пишет в журнал. Используется, пока адаптер Telegram выключен или администраторы не заданы. */
object LoggingAdminNotifier : AdminNotifier {
    override suspend fun notifyAdmins(text: String) {
        log.warn { "Оповещение администратору: $text" }
    }
}
