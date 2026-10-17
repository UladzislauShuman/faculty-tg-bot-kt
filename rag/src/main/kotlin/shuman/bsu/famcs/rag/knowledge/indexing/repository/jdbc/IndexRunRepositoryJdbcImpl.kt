package shuman.bsu.famcs.rag.knowledge.indexing.repository.jdbc

import shuman.bsu.famcs.rag.knowledge.indexing.repository.IndexRunRepository
import shuman.bsu.famcs.rag.knowledge.model.IndexCounters
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.model.IndexRunStatus
import shuman.bsu.famcs.rag.platform.database.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Реализация [IndexRunRepository] на PostgreSQL (таблица `index_run`).
 *
 * @property db доступ к базе.
 */
class IndexRunRepositoryJdbcImpl(
    private val db: Database,
) : IndexRunRepository {
    override suspend fun start(
        mode: IndexMode,
        at: Instant,
    ): Long = db.query { jdbc ->
        jdbc
            .sql("INSERT INTO index_run (mode, started_at, status) VALUES (:mode, :at, 'RUNNING') RETURNING id")
            .param("mode", mode.name)
            .param("at", Timestamp.from(at))
            .query(Long::class.java)
            .single()
    }

    override suspend fun finish(
        id: Long,
        status: IndexRunStatus,
        counters: IndexCounters,
        message: String?,
        at: Instant,
    ) {
        db.query { jdbc ->
            jdbc
                .sql(
                    """
                UPDATE index_run SET finished_at = :at, status = :status, pages_found = :found, pages_changed = :changed,
                                     pages_failed = :failed, pages_removed = :removed, message = :message
                WHERE id = :id
                    """.trimIndent(),
                ).param("at", Timestamp.from(at))
                .param("status", status.name)
                .param("found", counters.found)
                .param("changed", counters.changed)
                .param("failed", counters.failed)
                .param("removed", counters.removed)
                .param("message", message)
                .param("id", id)
                .update()
        }
    }
}
