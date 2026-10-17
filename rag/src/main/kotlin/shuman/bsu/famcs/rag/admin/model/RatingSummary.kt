package shuman.bsu.famcs.rag.admin.model

import java.math.BigDecimal

/**
 * Сводка оценок пользователей за период.
 *
 * @property count сколько оценок поставлено.
 * @property average средняя оценка; 0, если оценок нет.
 */
data class RatingSummary(
    val count: Int,
    val average: BigDecimal,
)
