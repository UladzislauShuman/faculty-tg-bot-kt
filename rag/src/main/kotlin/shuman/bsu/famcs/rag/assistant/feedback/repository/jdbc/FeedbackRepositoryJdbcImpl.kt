package shuman.bsu.famcs.rag.assistant.feedback.repository.jdbc

import shuman.bsu.famcs.rag.assistant.feedback.repository.FeedbackRepository
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import java.sql.Timestamp
import java.time.Instant

/**
 * Реализация [FeedbackRepository] на PostgreSQL.
 *
 * @property db доступ к базе.
 * @property hasher хеш пользователя: по нему проверяется, что оценивается собственный ответ.
 */
class FeedbackRepositoryJdbcImpl(
    private val db: Database,
    private val hasher: UserHasher,
) : FeedbackRepository {
    override suspend fun save(
        answerId: AnswerId,
        userId: TelegramId,
        rating: Int,
        reason: String?,
        at: Instant,
    ): Boolean = db.query { jdbc ->
        jdbc
            .sql(
                """
            INSERT INTO feedback (answer_id, telegram_id, rating, reason, created_at)
            SELECT a.id, :user, :rating, :reason, :at FROM answer_log a WHERE a.id = :answer AND a.user_hash = :hash
            ON CONFLICT (answer_id, telegram_id) DO UPDATE SET rating = :rating, reason = :reason, created_at = :at
                """.trimIndent(),
            ).param("user", userId.value)
            .param("rating", rating)
            .param("reason", reason)
            .param("at", Timestamp.from(at))
            .param("answer", answerId.value)
            .param("hash", hasher.hash(userId).value)
            .update() == 1
    }
}
