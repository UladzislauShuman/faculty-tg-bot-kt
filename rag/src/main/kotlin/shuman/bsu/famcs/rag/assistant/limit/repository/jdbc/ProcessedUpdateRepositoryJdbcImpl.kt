package shuman.bsu.famcs.rag.assistant.limit.repository.jdbc

import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.platform.database.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Реализация [ProcessedUpdateRepository] на PostgreSQL (таблица `processed_update`).
 *
 * @property db доступ к базе.
 */
class ProcessedUpdateRepositoryJdbcImpl(
    private val db: Database,
) : ProcessedUpdateRepository {
    override suspend fun registerIfNew(
        updateId: Long,
        at: Instant,
    ): Boolean = db.query { jdbc ->
        jdbc
            .sql("INSERT INTO processed_update (update_id, received_at) VALUES (:id, :at) ON CONFLICT (update_id) DO NOTHING")
            .param("id", updateId)
            .param("at", Timestamp.from(at))
            .update() == 1
    }

    override suspend fun purgeOlderThan(cutoff: Instant): Int = db.query { jdbc ->
        jdbc.sql("DELETE FROM processed_update WHERE received_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update()
    }
}
