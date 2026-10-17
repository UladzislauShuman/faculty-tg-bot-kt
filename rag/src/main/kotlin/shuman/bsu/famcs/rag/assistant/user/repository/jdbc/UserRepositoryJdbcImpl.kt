package shuman.bsu.famcs.rag.assistant.user.repository.jdbc

import shuman.bsu.famcs.rag.api.model.UserRecord
import shuman.bsu.famcs.rag.assistant.user.repository.UserRepository
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import java.sql.Timestamp
import java.time.Instant

/**
 * Реализация [UserRepository] на PostgreSQL через [Database].
 *
 * @property db доступ к базе: блокирующие вызовы выполняются на отдельном ограниченном пуле.
 * @property hasher хеш пользователя, по которому журнал ответов обезличивается при удалении.
 */
class UserRepositoryJdbcImpl(
    private val db: Database,
    private val hasher: UserHasher,
) : UserRepository {
    override suspend fun touch(
        userId: TelegramId,
        at: Instant,
    ): UserRecord = db.query { jdbc ->
        jdbc
            .sql(
                """
            INSERT INTO app_user (telegram_id, last_seen_at, active, created_at)
            VALUES (:id, :at, TRUE, :at)
            ON CONFLICT (telegram_id) DO UPDATE SET last_seen_at = :at, active = TRUE
            RETURNING consent_version, consent_at
                """.trimIndent(),
            ).param("id", userId.value)
            .param("at", Timestamp.from(at))
            .query { rs, _ -> UserRecord(rs.getString("consent_version"), rs.getTimestamp("consent_at")?.toInstant()) }
            .single()
    }

    override suspend fun saveConsent(
        userId: TelegramId,
        version: String,
        at: Instant,
    ) {
        db.query { jdbc ->
            jdbc
                .sql("UPDATE app_user SET consent_version = :version, consent_at = :at WHERE telegram_id = :id")
                .param("version", version)
                .param("at", Timestamp.from(at))
                .param("id", userId.value)
                .update()
        }
    }

    override suspend fun markInactive(userId: TelegramId) {
        db.query { jdbc -> jdbc.sql("UPDATE app_user SET active = FALSE WHERE telegram_id = :id").param("id", userId.value).update() }
    }

    override suspend fun erase(userId: TelegramId) {
        val hash = hasher.hash(userId).value
        db.transaction { jdbc ->
            // Журнал ответов связан с пользователем только хешем, поэтому его нужно обезличить отдельно от каскадного удаления.
            jdbc
                .sql("UPDATE answer_log SET user_hash = NULL, rewritten_query = NULL WHERE user_hash = :hash")
                .param("hash", hash)
                .update()
            // Каскад удаляет сессии, сообщения, оценки и счётчики пользователя.
            jdbc.sql("DELETE FROM app_user WHERE telegram_id = :id").param("id", userId.value).update()
        }
    }
}
