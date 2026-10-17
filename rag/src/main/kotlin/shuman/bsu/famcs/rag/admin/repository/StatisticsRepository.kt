package shuman.bsu.famcs.rag.admin.repository

import shuman.bsu.famcs.rag.admin.model.AnswerQuality
import shuman.bsu.famcs.rag.admin.model.KnowledgeGap
import shuman.bsu.famcs.rag.admin.model.LowRatedAnswer
import shuman.bsu.famcs.rag.admin.model.NamedCount
import shuman.bsu.famcs.rag.admin.model.RatingSummary
import shuman.bsu.famcs.rag.admin.model.UserActivity
import java.time.Instant

/**
 * Выборки для административных отчётов: только чтение журнала ответов, оценок и пользователей.
 * Все периоды задаются нижней границей `since`: считаются записи, созданные позже неё.
 */
interface StatisticsRepository {
    /** Сколько пользователей всего и сколько из них обращались после [since]. */
    suspend fun userActivity(since: Instant): UserActivity

    /** Число ответов по статусам после [since], от самого частого. */
    suspend fun countByStatus(since: Instant): List<NamedCount>

    /** Число отвеченных вопросов по веткам после [since], от самой частой. */
    suspend fun countByRoute(since: Instant): List<NamedCount>

    /** Время ответа, расход токенов и доля «не нашёл» после [since]. */
    suspend fun answerQuality(since: Instant): AnswerQuality

    /** Количество и средняя оценка после [since]. */
    suspend fun ratingSummary(since: Instant): RatingSummary

    /** Последние ответы с оценкой не выше двух, не больше [limit] штук. */
    suspend fun lowRated(limit: Int): List<LowRatedAnswer>

    /** Самые частые вопросы без ответа после [since], не больше [limit] строк. */
    suspend fun knowledgeGaps(
        since: Instant,
        limit: Int,
    ): List<KnowledgeGap>
}
