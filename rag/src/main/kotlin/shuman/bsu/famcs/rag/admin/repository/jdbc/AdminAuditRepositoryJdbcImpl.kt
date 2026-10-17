package shuman.bsu.famcs.rag.admin.repository.jdbc

import shuman.bsu.famcs.rag.admin.repository.AdminAuditRepository
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.sql.Timestamp
import java.time.Instant

/**
 * Реализация [AdminAuditRepository] на PostgreSQL.
 *
 * @property db доступ к базе.
 */
class AdminAuditRepositoryJdbcImpl(
    private val db: Database,
) : AdminAuditRepository {
    override suspend fun record(
        adminId: TelegramId,
        command: String,
        at: Instant,
    ) {
        db.query { jdbc ->
            jdbc
                .sql("INSERT INTO admin_audit (admin_id, command, at) VALUES (:admin, :command, :at)")
                .param("admin", adminId.value)
                .param("command", command)
                .param("at", Timestamp.from(at))
                .update()
        }
    }
}
