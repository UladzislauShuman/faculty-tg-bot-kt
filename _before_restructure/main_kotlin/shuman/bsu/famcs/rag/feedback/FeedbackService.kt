package shuman.bsu.famcs.rag.feedback

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UserHasher
import java.sql.Timestamp
import java.time.Clock

private val log = KotlinLogging.logger {}

/** Допустимые оценки ответа. */
val RATING_RANGE = 1..5

/**
 * Оценки ответов пользователями (1–5), по кнопкам под ответом.
 *
 * Оценить можно только свой ответ: номер записи журнала угадывается, поэтому принадлежность проверяется по хешу пользователя.
 * Повторная оценка того же ответа перезаписывает прежнюю.
 */
interface FeedbackService {
    /** Сохраняет оценку. Возвращает `false`, если ответ не найден или принадлежит другому пользователю. */
    suspend fun rate(
        answerId: AnswerId,
        userId: TelegramId,
        rating: Int,
        reason: String? = null,
    ): Boolean
}

class JdbcFeedbackService(
    private val db: Database,
    private val hasher: UserHasher,
    private val clock: Clock,
) : FeedbackService {
    override suspend fun rate(
        answerId: AnswerId,
        userId: TelegramId,
        rating: Int,
        reason: String?,
    ): Boolean {
        require(rating in RATING_RANGE) { "Оценка должна быть от ${RATING_RANGE.first} до ${RATING_RANGE.last}" }
        val saved = db.query { jdbc ->
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
                .param("at", Timestamp.from(clock.instant()))
                .param("answer", answerId.value)
                .param("hash", hasher.hash(userId).value)
                .update() == 1
        }
        if (!saved) log.warn { "Оценка отклонена: ответ ${answerId.value} не найден или чужой" }
        return saved
    }
}
