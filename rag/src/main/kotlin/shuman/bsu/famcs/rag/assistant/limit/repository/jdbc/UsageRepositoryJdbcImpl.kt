package shuman.bsu.famcs.rag.assistant.limit.repository.jdbc

import shuman.bsu.famcs.rag.assistant.limit.model.SystemUsage
import shuman.bsu.famcs.rag.assistant.limit.repository.UsageRepository
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import java.sql.Date
import java.time.LocalDate

/**
 * Счётчики в Postgres. Пользователь в таблице представлен хешем ([UserHasher]), а не Telegram ID: счётчик переживает `/forget`
 * (иначе лимит обходился бы повторной регистрацией) и при этом не позволяет узнать человека без секрета.
 */
class UsageRepositoryJdbcImpl(
    private val db: Database,
    private val hasher: UserHasher,
) : UsageRepository {
    override suspend fun userMessages(
        userId: TelegramId,
        day: LocalDate,
    ): Int = db.query { jdbc ->
        jdbc
            .sql("SELECT messages FROM usage_counter WHERE user_hash = :hash AND day = :day")
            .param("hash", hasher.hash(userId).value)
            .param("day", Date.valueOf(day))
            .query(Int::class.java)
            .optional()
            .orElse(0)
    }

    override suspend fun recordMessage(
        userId: TelegramId,
        day: LocalDate,
        tokens: Long,
    ) {
        db.transaction { jdbc ->
            jdbc
                .sql(
                    """
                INSERT INTO usage_counter (user_hash, day, messages, tokens) VALUES (:hash, :day, 1, :tokens)
                ON CONFLICT (user_hash, day) DO UPDATE SET messages = usage_counter.messages + 1, tokens = usage_counter.tokens + :tokens
                    """.trimIndent(),
                ).param("hash", hasher.hash(userId).value)
                .param("day", Date.valueOf(day))
                .param("tokens", tokens)
                .update()
            jdbc
                .sql(
                    """
                INSERT INTO system_usage (day, messages, tokens) VALUES (:day, 1, 0)
                ON CONFLICT (day) DO UPDATE SET messages = system_usage.messages + 1
                    """.trimIndent(),
                ).param("day", Date.valueOf(day))
                .update()
        }
    }

    override suspend fun addSystemTokens(
        day: LocalDate,
        tokens: Long,
    ): Long = db.query { jdbc ->
        jdbc
            .sql(
                """
            INSERT INTO system_usage (day, messages, tokens) VALUES (:day, 0, :tokens)
            ON CONFLICT (day) DO UPDATE SET tokens = system_usage.tokens + :tokens
            RETURNING tokens
                """.trimIndent(),
            ).param("day", Date.valueOf(day))
            .param("tokens", tokens)
            .query(Long::class.java)
            .single()
    }

    override suspend fun system(day: LocalDate): SystemUsage = db.query { jdbc ->
        jdbc
            .sql("SELECT messages, tokens FROM system_usage WHERE day = :day")
            .param("day", Date.valueOf(day))
            .query { rs, _ -> SystemUsage(rs.getInt("messages"), rs.getLong("tokens")) }
            .optional()
            .orElse(SystemUsage(messages = 0, tokens = 0))
    }
}
