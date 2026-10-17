package shuman.bsu.famcs.rag.assistant.feedback.repository.jdbc

import shuman.bsu.famcs.rag.assistant.feedback.model.AnswerLogEntry
import shuman.bsu.famcs.rag.assistant.feedback.repository.AnswerLogRepository
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import java.sql.Timestamp

/**
 * Реализация [AnswerLogRepository] на PostgreSQL (таблица `answer_log`).
 *
 * @property db доступ к базе.
 */
class AnswerLogRepositoryJdbcImpl(
    private val db: Database,
) : AnswerLogRepository {
    override suspend fun append(entry: AnswerLogEntry): AnswerId = db.query { jdbc ->
        val id = jdbc
            .sql(
                """
            INSERT INTO answer_log (message_id, user_hash, rewritten_query, route, status, no_info, doc_ids, provider, model,
                                    tokens_in, tokens_out, latency_ms, created_at)
            VALUES (:messageId, :hash, :query, :route, :status, :noInfo, :docs, :provider, :model, :tokensIn, :tokensOut, :latency, :at)
            RETURNING id
                """.trimIndent(),
            ).param("messageId", entry.messageId)
            .param("hash", entry.userHash.value)
            .param("query", entry.query)
            .param("route", entry.route?.name ?: NO_ROUTE)
            .param("status", entry.status.name)
            .param("noInfo", entry.noInfo)
            .param("docs", entry.sources.joinToString(" ").ifEmpty { null })
            .param("provider", entry.provider)
            .param("model", entry.model)
            .param("tokensIn", entry.tokensIn)
            .param("tokensOut", entry.tokensOut)
            .param("latency", entry.latencyMs)
            .param("at", Timestamp.from(entry.createdAt))
            .query(Long::class.java)
            .single()
        AnswerId(id)
    }

    private companion object {
        /** Значение колонки `route` для вопросов, которые не дошли до классификатора. */
        const val NO_ROUTE = "NONE"
    }
}
