package shuman.bsu.famcs.rag.dialog

import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UserHasher
import java.sql.Timestamp
import java.time.Instant

/**
 * Реестр пользователей: согласие на обработку данных и право на удаление.
 *
 * Хранится только Telegram ID, версия согласия и время обращений: имя, username и телефон бот не сохраняет.
 */
interface UserDirectory {
    /**
     * Фиксирует обращение: создаёт запись при первом сообщении, иначе обновляет время.
     * Заодно снимает отметку «заблокировал бота»: раз пишет, значит, снова доступен.
     */
    suspend fun visit(
        userId: TelegramId,
        at: Instant,
    ): UserRecord

    /** Записывает согласие вместе с версией текста, на который оно дано. */
    suspend fun grantConsent(
        userId: TelegramId,
        version: String,
        at: Instant,
    )

    /** Пользователь заблокировал бота: ответы ему больше не отправляются, пока он не напишет снова. */
    suspend fun markBlocked(userId: TelegramId)

    /**
     * Полное удаление данных пользователя (`/forget`): сессии, сообщения, оценки, счётчики.
     * В журнале ответов остаются обезличенные строки для статистики: хеш пользователя и текст запроса стираются.
     */
    suspend fun forget(userId: TelegramId)
}

class JdbcUserDirectory(
    private val db: Database,
    private val hasher: UserHasher,
) : UserDirectory {
    override suspend fun visit(
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

    override suspend fun grantConsent(
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

    override suspend fun markBlocked(userId: TelegramId) {
        db.query { jdbc -> jdbc.sql("UPDATE app_user SET active = FALSE WHERE telegram_id = :id").param("id", userId.value).update() }
    }

    override suspend fun forget(userId: TelegramId) {
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
