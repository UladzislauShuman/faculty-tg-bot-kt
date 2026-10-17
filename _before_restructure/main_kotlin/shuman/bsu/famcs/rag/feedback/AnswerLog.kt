package shuman.bsu.famcs.rag.feedback

import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.UserHash
import shuman.bsu.famcs.rag.core.AnswerStatus
import shuman.bsu.famcs.rag.intent.Intent
import java.sql.Timestamp
import java.time.Instant

/**
 * Запись журнала ответов: что и как ответил бот, без Telegram ID. Нужна для оценки качества и поиска пробелов в знаниях.
 * Пользователь связан с записью только хешем с секретом; при `/forget` хеш и текст запроса стираются.
 */
data class AnswerLogEntry(
    /** Сообщение-ответ в диалоге. `null`, если ответа пользователю не было (сбой, перегрузка). */
    val messageId: Long?,
    val userHash: UserHash,
    /** Запрос для поиска (после переформулирования). Хранится ограниченное время (см. `bot.retention`). */
    val query: String?,
    /** Выбранная ветка. `null`, если вопрос не дошёл до классификатора. */
    val route: Intent?,
    val status: AnswerStatus,
    val noInfo: Boolean,
    /** Адреса источников, на которые опирался ответ. */
    val sources: List<String>,
    val provider: String?,
    val model: String?,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Int,
    val createdAt: Instant,
)

/** Журнал ответов. */
interface AnswerLog {
    /** Добавляет запись и возвращает её номер: к нему привязываются кнопки оценки. */
    suspend fun append(entry: AnswerLogEntry): AnswerId
}

class JdbcAnswerLog(
    private val db: Database,
) : AnswerLog {
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
