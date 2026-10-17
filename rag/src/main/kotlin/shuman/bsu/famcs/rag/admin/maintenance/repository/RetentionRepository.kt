package shuman.bsu.famcs.rag.admin.maintenance.repository

import java.time.Instant
import java.time.LocalDate

/** Удаление устаревших записей, которых нет в других хранилищах. */
interface RetentionRepository {
    /** Удаляет записи журнала ответов старше [cutoff] вместе с их оценками. */
    suspend fun deleteAnswerLogOlderThan(cutoff: Instant): Int

    /** Удаляет суточные счётчики пользователей до [day]. */
    suspend fun deleteUsageBefore(day: LocalDate): Int

    /** Удаляет сессии без сообщений, где давно не было активности. */
    suspend fun deleteEmptySessionsOlderThan(cutoff: Instant): Int
}
