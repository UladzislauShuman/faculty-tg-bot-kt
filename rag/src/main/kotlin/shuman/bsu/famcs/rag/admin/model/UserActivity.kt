package shuman.bsu.famcs.rag.admin.model

/**
 * Сводка по пользователям для `/admin_stats`.
 *
 * @property total сколько пользователей зарегистрировано за всё время.
 * @property recent сколько из них обращались к боту в расчётном периоде.
 */
data class UserActivity(
    val total: Int,
    val recent: Int,
)
