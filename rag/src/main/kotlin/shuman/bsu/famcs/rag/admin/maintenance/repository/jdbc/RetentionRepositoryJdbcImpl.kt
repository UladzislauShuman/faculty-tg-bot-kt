package shuman.bsu.famcs.rag.admin.maintenance.repository.jdbc

import shuman.bsu.famcs.rag.admin.maintenance.repository.RetentionRepository
import shuman.bsu.famcs.rag.platform.database.Database
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * Реализация [RetentionRepository] на PostgreSQL: удаление записей, у которых вышел срок хранения.
 *
 * @property db доступ к базе.
 */
class RetentionRepositoryJdbcImpl(
    private val db: Database,
) : RetentionRepository {
    override suspend fun deleteAnswerLogOlderThan(cutoff: Instant): Int = db.query { jdbc ->
        jdbc.sql("DELETE FROM answer_log WHERE created_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update()
    }

    override suspend fun deleteUsageBefore(day: LocalDate): Int = db.query { jdbc ->
        jdbc.sql("DELETE FROM usage_counter WHERE day < :day").param("day", Date.valueOf(day)).update()
    }

    override suspend fun deleteEmptySessionsOlderThan(cutoff: Instant): Int = db.query { jdbc ->
        jdbc
            .sql(
                "DELETE FROM chat_session s WHERE s.last_activity_at < :cutoff AND NOT EXISTS (SELECT 1 FROM message m WHERE m.session_id = s.id)",
            ).param("cutoff", Timestamp.from(cutoff))
            .update()
    }
}
