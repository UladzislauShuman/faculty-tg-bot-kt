package shuman.bsu.famcs.rag.platform.notification

/**
 * Оповещение администраторов: расход бюджета, сбои провайдеров, завершение индексации.
 * Реализация для Telegram живёт в адаптере; ядро знает только этот интерфейс.
 */
fun interface AdminNotifier {
    /** Отправляет сообщение всем администраторам. Сбой отправки не должен ломать вызывающий код. */
    suspend fun notifyAdmins(text: String)
}
