package shuman.bsu.famcs.rag.admin.repository.jdbc

import shuman.bsu.famcs.rag.admin.model.AnswerQuality
import shuman.bsu.famcs.rag.admin.model.KnowledgeGap
import shuman.bsu.famcs.rag.admin.model.LowRatedAnswer
import shuman.bsu.famcs.rag.admin.model.NamedCount
import shuman.bsu.famcs.rag.admin.model.RatingSummary
import shuman.bsu.famcs.rag.admin.model.UserActivity
import shuman.bsu.famcs.rag.admin.repository.StatisticsRepository
import shuman.bsu.famcs.rag.platform.database.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Реализация [StatisticsRepository] на PostgreSQL.
 *
 * @property db доступ к базе.
 */
class StatisticsRepositoryJdbcImpl(
    private val db: Database,
) : StatisticsRepository {
    override suspend fun userActivity(since: Instant): UserActivity = db.query { jdbc ->
        jdbc
            .sql("SELECT count(*) AS total, count(*) FILTER (WHERE last_seen_at > :since) AS recent FROM app_user")
            .param("since", Timestamp.from(since))
            .query { rs, _ -> UserActivity(rs.getInt("total"), rs.getInt("recent")) }
            .single()
    }

    override suspend fun countByStatus(since: Instant): List<NamedCount> = db.query { jdbc ->
        jdbc
            .sql("SELECT status, count(*) AS n FROM answer_log WHERE created_at > :since GROUP BY status ORDER BY n DESC")
            .param("since", Timestamp.from(since))
            .query { rs, _ -> NamedCount(rs.getString("status"), rs.getInt("n")) }
            .list()
    }

    override suspend fun countByRoute(since: Instant): List<NamedCount> = db.query { jdbc ->
        jdbc
            .sql(
                """
            SELECT route, count(*) AS n FROM answer_log
            WHERE created_at > :since AND status IN ('ANSWERED', 'NO_INFO') GROUP BY route ORDER BY n DESC
                """.trimIndent(),
            ).param("since", Timestamp.from(since))
            .query { rs, _ -> NamedCount(rs.getString("route"), rs.getInt("n")) }
            .list()
    }

    override suspend fun answerQuality(since: Instant): AnswerQuality = db.query { jdbc ->
        jdbc
            .sql(
                """
            SELECT count(*) AS answered,
                   count(*) FILTER (WHERE no_info) AS no_info,
                   coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms), 0) AS p50,
                   coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95,
                   coalesce(sum(tokens_in), 0) AS tokens_in, coalesce(sum(tokens_out), 0) AS tokens_out
            FROM answer_log WHERE created_at > :since AND status IN ('ANSWERED', 'NO_INFO')
                """.trimIndent(),
            ).param("since", Timestamp.from(since))
            .query { rs, _ ->
                AnswerQuality(
                    answered = rs.getInt("answered"),
                    noInfo = rs.getInt("no_info"),
                    medianLatencyMs = rs.getLong("p50"),
                    p95LatencyMs = rs.getLong("p95"),
                    tokensIn = rs.getLong("tokens_in"),
                    tokensOut = rs.getLong("tokens_out"),
                )
            }.single()
    }

    override suspend fun ratingSummary(since: Instant): RatingSummary = db.query { jdbc ->
        jdbc
            .sql("SELECT count(*) AS n, coalesce(round(avg(rating), 2), 0) AS avg FROM feedback WHERE created_at > :since")
            .param("since", Timestamp.from(since))
            .query { rs, _ -> RatingSummary(rs.getInt("n"), rs.getBigDecimal("avg")) }
            .single()
    }

    override suspend fun lowRated(limit: Int): List<LowRatedAnswer> = db.query { jdbc ->
        jdbc
            .sql(
                """
            SELECT f.rating, f.reason, f.comment, a.rewritten_query, m.body
            FROM feedback f JOIN answer_log a ON a.id = f.answer_id LEFT JOIN message m ON m.id = a.message_id
            WHERE f.rating <= 2 ORDER BY f.created_at DESC LIMIT :limit
                """.trimIndent(),
            ).param("limit", limit)
            .query { rs, _ ->
                LowRatedAnswer(
                    rating = rs.getInt("rating"),
                    reason = rs.getString("reason"),
                    comment = rs.getString("comment"),
                    query = rs.getString("rewritten_query"),
                    answer = rs.getString("body"),
                )
            }.list()
    }

    override suspend fun knowledgeGaps(
        since: Instant,
        limit: Int,
    ): List<KnowledgeGap> = db.query { jdbc ->
        jdbc
            .sql(
                """
            SELECT rewritten_query, count(*) AS n FROM answer_log
            WHERE no_info AND rewritten_query IS NOT NULL AND created_at > :since
            GROUP BY rewritten_query ORDER BY n DESC, max(created_at) DESC LIMIT :limit
                """.trimIndent(),
            ).param("since", Timestamp.from(since))
            .param("limit", limit)
            .query { rs, _ -> KnowledgeGap(rs.getString("rewritten_query"), rs.getInt("n")) }
            .list()
    }
}
