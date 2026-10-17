package shuman.bsu.famcs.rag.assistant.dialog.repository.jdbc

import shuman.bsu.famcs.rag.assistant.dialog.model.Author
import shuman.bsu.famcs.rag.assistant.dialog.model.ChatSession
import shuman.bsu.famcs.rag.assistant.dialog.model.SessionSummary
import shuman.bsu.famcs.rag.assistant.dialog.model.StoredMessage
import shuman.bsu.famcs.rag.assistant.dialog.repository.ConversationRepository
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.identifier.SessionId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Реализация [ConversationRepository] на PostgreSQL (таблицы `chat_session` и `message`).
 *
 * @property db доступ к базе.
 */
class ConversationRepositoryJdbcImpl(
    private val db: Database,
) : ConversationRepository {
    override suspend fun activeSession(
        userId: TelegramId,
        now: Instant,
        idleTimeout: Duration,
    ): ChatSession = db.transaction { jdbc ->
        val active = jdbc
            .sql("SELECT $SESSION_COLUMNS FROM chat_session WHERE telegram_id = :id AND closed_at IS NULL FOR UPDATE")
            .param("id", userId.value)
            .query { rs, _ -> rs.toSession() }
            .optional()
            .orElse(null)

        if (active != null && Duration.between(active.lastActivityAt, now) <= idleTimeout) {
            return@transaction active
        }
        if (active != null) {
            // Сессия закрывается тем моментом, когда диалог на самом деле закончился, а не когда пользователь вернулся.
            jdbc
                .sql("UPDATE chat_session SET closed_at = :at WHERE id = :sessionId")
                .param("at", Timestamp.from(active.lastActivityAt))
                .param("sessionId", active.id.value)
                .update()
        }
        // При одновременном создании сессии вторая вставка молча пропускается (частичный уникальный индекс), затем читаем победившую.
        jdbc
            .sql(
                """
            INSERT INTO chat_session (telegram_id, started_at, last_activity_at)
            VALUES (:id, :at, :at)
            ON CONFLICT (telegram_id) WHERE closed_at IS NULL DO NOTHING
                """.trimIndent(),
            ).param("id", userId.value)
            .param("at", Timestamp.from(now))
            .update()

        jdbc
            .sql("SELECT $SESSION_COLUMNS FROM chat_session WHERE telegram_id = :id AND closed_at IS NULL")
            .param("id", userId.value)
            .query { rs, _ -> rs.toSession() }
            .single()
    }

    override suspend fun closeSession(
        userId: TelegramId,
        now: Instant,
    ) {
        db.query { jdbc ->
            jdbc
                .sql("UPDATE chat_session SET closed_at = :at WHERE telegram_id = :id AND closed_at IS NULL")
                .param("at", Timestamp.from(now))
                .param("id", userId.value)
                .update()
        }
    }

    override suspend fun appendExchange(
        sessionId: SessionId,
        question: String,
        answer: String,
        at: Instant,
    ): Long = db.transaction { jdbc ->
        val insert = "INSERT INTO message (session_id, author, body, created_at) VALUES (:session, :author, :body, :at) RETURNING id"
        // Идентификаторы выдаёт последовательность по порядку вставки: вопрос получает номер меньше, чем ответ.
        jdbc
            .sql(insert)
            .param("session", sessionId.value)
            .param("author", Author.USER.name)
            .param("body", question)
            .param("at", Timestamp.from(at))
            .query(Long::class.java)
            .single()
        val answerId = jdbc
            .sql(insert)
            .param("session", sessionId.value)
            .param("author", Author.ASSISTANT.name)
            .param("body", answer)
            .param("at", Timestamp.from(at))
            .query(Long::class.java)
            .single()
        jdbc
            .sql("UPDATE chat_session SET last_activity_at = :at WHERE id = :session")
            .param("at", Timestamp.from(at))
            .param("session", sessionId.value)
            .update()
        answerId
    }

    override suspend fun recentMessages(
        sessionId: SessionId,
        limit: Int,
    ): List<StoredMessage> = db.query { jdbc ->
        jdbc
            .sql("SELECT id, author, body, created_at FROM message WHERE session_id = :session ORDER BY id DESC LIMIT :limit")
            .param("session", sessionId.value)
            .param("limit", limit)
            .query { rs, _ ->
                StoredMessage(
                    rs.getLong("id"),
                    Author.valueOf(rs.getString("author")),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            }.list()
            .asReversed()
    }

    override suspend fun summaryOf(sessionId: SessionId): SessionSummary = db.query { jdbc ->
        jdbc
            .sql("SELECT summary, summarized_until_message_id FROM chat_session WHERE id = :session")
            .param("session", sessionId.value)
            .query { rs, _ -> SessionSummary(rs.getString("summary"), rs.getLong("summarized_until_message_id")) }
            .optional()
            .orElse(SessionSummary(text = null, summarizedUntilMessageId = Long.MAX_VALUE))
    }

    override suspend fun saveSummary(
        sessionId: SessionId,
        summary: String,
        summarizedUntilMessageId: Long,
    ) {
        db.query { jdbc ->
            // Условие «номер только растёт» не даёт медленной сводке затереть более свежую.
            jdbc
                .sql(
                    """
                UPDATE chat_session SET summary = :summary, summarized_until_message_id = :until
                WHERE id = :session AND summarized_until_message_id < :until
                    """.trimIndent(),
                ).param("summary", summary)
                .param("until", summarizedUntilMessageId)
                .param("session", sessionId.value)
                .update()
        }
    }

    override suspend fun deleteMessagesOlderThan(cutoff: Instant): Int = db.query { jdbc ->
        jdbc.sql("DELETE FROM message WHERE created_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update()
    }

    /** Читает строку таблицы `chat_session` в модель сессии. */
    private fun ResultSet.toSession() = ChatSession(
        id = SessionId(getLong("id")),
        userId = TelegramId(getLong("telegram_id")),
        summary = getString("summary"),
        summarizedUntilMessageId = getLong("summarized_until_message_id"),
        startedAt = getTimestamp("started_at").toInstant(),
        lastActivityAt = getTimestamp("last_activity_at").toInstant(),
    )

    private companion object {
        const val SESSION_COLUMNS = "id, telegram_id, summary, summarized_until_message_id, started_at, last_activity_at"
    }
}
