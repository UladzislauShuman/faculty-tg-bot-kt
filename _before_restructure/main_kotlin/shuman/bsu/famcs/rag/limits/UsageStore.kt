package shuman.bsu.famcs.rag.limits

import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UserHasher
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** Суточный расход всей системы. */
data class SystemUsage(
    val messages: Int,
    val tokens: Long,
)

/**
 * Суточные счётчики: вопросы и токены пользователя и всей системы. По ним работают лимиты и режим экономии.
 * Хранятся в Postgres, поэтому не обнуляются перезапуском бота.
 */
interface UsageStore {
    /** Сколько вопросов пользователь уже задал в этот день. */
    suspend fun userMessages(
        userId: TelegramId,
        day: LocalDate,
    ): Int

    /** Записывает завершённый вопрос: у пользователя и у системы. [tokens] — расход токенов на этот вопрос. */
    suspend fun recordMessage(
        userId: TelegramId,
        day: LocalDate,
        tokens: Long,
    )

    /** Добавляет расход токенов в счётчик системы (любой вызов модели, в том числе фоновые сводки). Возвращает итог за день. */
    suspend fun addSystemTokens(
        day: LocalDate,
        tokens: Long,
    ): Long

    suspend fun system(day: LocalDate): SystemUsage
}

/** Номера уже обработанных обновлений Telegram: повторная доставка не обрабатывается и не тратит токены. */
interface ProcessedUpdates {
    /** Регистрирует номер. `true` — обновление новое, `false` — уже обрабатывалось. */
    suspend fun registerIfNew(
        updateId: Long,
        at: Instant,
    ): Boolean

    /** Удаляет записи старше [cutoff]. Возвращает число удалённых. */
    suspend fun purgeOlderThan(cutoff: Instant): Int
}

/**
 * Счётчики в Postgres. Пользователь в таблице представлен хешем ([UserHasher]), а не Telegram ID: счётчик переживает `/forget`
 * (иначе лимит обходился бы повторной регистрацией) и при этом не позволяет узнать человека без секрета.
 */
class JdbcUsageStore(
    private val db: Database,
    private val hasher: UserHasher,
) : UsageStore {
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

class JdbcProcessedUpdates(
    private val db: Database,
) : ProcessedUpdates {
    override suspend fun registerIfNew(
        updateId: Long,
        at: Instant,
    ): Boolean = db.query { jdbc ->
        jdbc
            .sql("INSERT INTO processed_update (update_id, received_at) VALUES (:id, :at) ON CONFLICT (update_id) DO NOTHING")
            .param("id", updateId)
            .param("at", Timestamp.from(at))
            .update() == 1
    }

    override suspend fun purgeOlderThan(cutoff: Instant): Int = db.query { jdbc ->
        jdbc.sql("DELETE FROM processed_update WHERE received_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update()
    }
}
