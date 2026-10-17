package shuman.bsu.famcs.rag.dialog

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UserHasher
import shuman.bsu.famcs.rag.testsupport.TestPostgres
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилища диалога на настоящем PostgreSQL (нужен Docker): реестр пользователей, сессии, сообщения, сводка, право на забвение.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DialogStoresIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = TestPostgres.container()
    }

    private lateinit var db: Database
    private lateinit var users: JdbcUserDirectory
    private lateinit var conversations: JdbcConversationStore
    private val hasher = UserHasher("integration-secret-0123456789", length = 16)
    private val t0: Instant = Instant.parse("2026-10-08T10:00:00Z")
    private val idle = Duration.ofMinutes(30)

    /** Каждый тест работает со своим пользователем: данные не пересекаются, очищать базу не нужно. */
    private val nextUser = AtomicLong(1000)

    private fun newUser() = TelegramId(nextUser.incrementAndGet())

    @BeforeAll
    fun connect() {
        db = TestPostgres.database(postgres)
        users = JdbcUserDirectory(db, hasher)
        conversations = JdbcConversationStore(db)
    }

    private suspend fun registered(): TelegramId = newUser().also { users.visit(it, t0) }

    // --- Реестр пользователей ---

    @Test
    fun `первое обращение создаёт пользователя без согласия, согласие записывается с версией`() = runBlocking<Unit> {
        val user = newUser()

        val first = users.visit(user, t0)
        assertNull(first.consentVersion)
        assertFalse(first.hasConsent("v1"))

        users.grantConsent(user, "v1", t0.plusSeconds(5))
        val second = users.visit(user, t0.plusSeconds(10))
        assertTrue(second.hasConsent("v1"))
        assertEquals(t0.plusSeconds(5), second.consentAt)
    }

    @Test
    fun `согласие на старую версию текста не действует на новую`() = runBlocking<Unit> {
        val user = registered()
        users.grantConsent(user, "v1", t0)

        assertFalse(users.visit(user, t0).hasConsent("v2"))
    }

    @Test
    fun `заблокировавший бота пользователь снова активен после нового сообщения`() = runBlocking<Unit> {
        val user = registered()
        users.markBlocked(user)
        assertEquals(false, activeFlag(user))

        users.visit(user, t0.plusSeconds(60))

        assertEquals(true, activeFlag(user))
    }

    @Test
    fun `забвение стирает данные пользователя и обезличивает журнал ответов`() = runBlocking<Unit> {
        val user = registered()
        users.grantConsent(user, "v1", t0)
        val session = conversations.activeSession(user, t0, idle)
        conversations.appendExchange(session.id, "вопрос", "ответ", t0)
        val answerId = insertAnswerLog(hasher.hash(user).value)
        db.query { jdbc ->
            jdbc
                .sql("INSERT INTO feedback (answer_id, telegram_id, rating, created_at) VALUES (:a, :u, 5, :at)")
                .param("a", answerId)
                .param("u", user.value)
                .param("at", Timestamp.from(t0))
                .update()
            jdbc
                .sql("INSERT INTO usage_counter (user_hash, day, messages, tokens) VALUES (:h, '2026-10-08', 3, 100)")
                .param("h", hasher.hash(user).value)
                .update()
        }

        users.forget(user)

        assertEquals(0, count("SELECT count(*) FROM app_user WHERE telegram_id = ?", user.value))
        assertEquals(0, count("SELECT count(*) FROM chat_session WHERE telegram_id = ?", user.value))
        assertEquals(0, count("SELECT count(*) FROM message WHERE session_id = ?", session.id.value))
        assertEquals(0, count("SELECT count(*) FROM feedback WHERE telegram_id = ?", user.value))
        // Счётчик дневного лимита остаётся (по хешу, без связи с Telegram ID): иначе /forget обнуляло бы лимит.
        val counter = db.query { jdbc ->
            jdbc
                .sql("SELECT messages FROM usage_counter WHERE user_hash = :h AND day = '2026-10-08'")
                .param("h", hasher.hash(user).value)
                .query(Int::class.java)
                .single()
        }
        assertEquals(3, counter)
        // Строка журнала остаётся для статистики, но без хеша пользователя и без текста его запроса.
        val row = db.query { jdbc ->
            jdbc
                .sql("SELECT user_hash, rewritten_query FROM answer_log WHERE id = :id")
                .param("id", answerId)
                .query { rs, _ -> rs.getString("user_hash") to rs.getString("rewritten_query") }
                .single()
        }
        assertEquals(null to null, row)
    }

    // --- Сессии и сообщения ---

    @Test
    fun `повторный вызов в пределах паузы возвращает ту же сессию`() = runBlocking<Unit> {
        val user = registered()

        val first = conversations.activeSession(user, t0, idle)
        val second = conversations.activeSession(user, t0.plus(Duration.ofMinutes(10)), idle)

        assertEquals(first.id, second.id)
    }

    @Test
    fun `после паузы открывается новая сессия, старая закрывается моментом последней активности`() = runBlocking<Unit> {
        val user = registered()
        val first = conversations.activeSession(user, t0, idle)
        conversations.appendExchange(first.id, "в", "о", t0.plusSeconds(60))

        val second = conversations.activeSession(user, t0.plus(Duration.ofHours(2)), idle)

        assertNotEquals(first.id, second.id)
        val closedAt = db.query { jdbc ->
            jdbc
                .sql("SELECT closed_at FROM chat_session WHERE id = :id")
                .param("id", first.id.value)
                .query { rs, _ -> rs.getTimestamp("closed_at").toInstant() }
                .single()
        }
        assertEquals(t0.plusSeconds(60), closedAt)
        assertEquals(1, count("SELECT count(*) FROM chat_session WHERE telegram_id = ? AND closed_at IS NULL", user.value))
    }

    @Test
    fun `одновременные первые вопросы одного пользователя дают одну сессию`() = runBlocking<Unit> {
        val user = registered()

        val ids = (1..8).map { async { conversations.activeSession(user, t0, idle).id } }.awaitAll()

        assertEquals(1, ids.toSet().size)
        assertEquals(1, count("SELECT count(*) FROM chat_session WHERE telegram_id = ? AND closed_at IS NULL", user.value))
    }

    @Test
    fun `новая сессия после команды new начинается с чистого листа`() = runBlocking<Unit> {
        val user = registered()
        val first = conversations.activeSession(user, t0, idle)
        conversations.appendExchange(first.id, "в", "о", t0)

        conversations.closeSession(user, t0.plusSeconds(5))
        val second = conversations.activeSession(user, t0.plusSeconds(10), idle)

        assertNotEquals(first.id, second.id)
        assertEquals(emptyList(), conversations.recentMessages(second.id, 10))
    }

    @Test
    fun `сообщения читаются по порядку, лимит оставляет самые свежие`() = runBlocking<Unit> {
        val user = registered()
        val session = conversations.activeSession(user, t0, idle)
        repeat(3) { conversations.appendExchange(session.id, "вопрос $it", "ответ $it", t0.plusSeconds(it.toLong())) }

        val all = conversations.recentMessages(session.id, 100)
        val lastThree = conversations.recentMessages(session.id, 3)

        assertEquals(6, all.size)
        assertEquals(listOf(Author.USER, Author.ASSISTANT), all.take(2).map { it.author })
        assertEquals(all.map { it.id }.sorted(), all.map { it.id }, "сообщения идут по возрастанию номера")
        assertEquals(listOf("ответ 1", "вопрос 2", "ответ 2"), lastThree.map { it.text })
    }

    @Test
    fun `сводка заменяется только более новой границей`() = runBlocking<Unit> {
        val user = registered()
        val session = conversations.activeSession(user, t0, idle)

        conversations.saveSummary(session.id, "первая", summarizedUntilMessageId = 10)
        conversations.saveSummary(session.id, "опоздавшая", summarizedUntilMessageId = 5)
        assertEquals(SessionSummary("первая", 10), conversations.summaryOf(session.id))

        conversations.saveSummary(session.id, "свежая", summarizedUntilMessageId = 12)
        assertEquals(SessionSummary("свежая", 12), conversations.summaryOf(session.id))
        assertEquals("свежая", conversations.activeSession(user, t0, idle).summary)
    }

    @Test
    fun `сообщения старше срока хранения удаляются, свежие остаются`() = runBlocking<Unit> {
        val user = registered()
        val session = conversations.activeSession(user, t0, idle)
        conversations.appendExchange(session.id, "старый вопрос", "старый ответ", t0.minus(Duration.ofDays(40)))
        conversations.appendExchange(session.id, "новый вопрос", "новый ответ", t0)

        val deleted = conversations.deleteMessagesOlderThan(t0.minus(Duration.ofDays(30)))

        assertEquals(2, deleted)
        assertEquals(listOf("новый вопрос", "новый ответ"), conversations.recentMessages(session.id, 10).map { it.text })
    }

    // --- Вспомогательное ---

    private suspend fun activeFlag(user: TelegramId): Boolean = db.query { jdbc ->
        jdbc
            .sql("SELECT active FROM app_user WHERE telegram_id = :id")
            .param("id", user.value)
            .query(Boolean::class.java)
            .single()
    }

    private suspend fun count(
        sql: String,
        argument: Long,
    ): Int = db.query { jdbc ->
        jdbc
            .sql(sql.replace("?", ":p"))
            .param("p", argument)
            .query(Int::class.java)
            .single()
    }

    private suspend fun insertAnswerLog(userHash: String): Long = db.query { jdbc ->
        jdbc
            .sql(
                """
            INSERT INTO answer_log (user_hash, rewritten_query, route, status, no_info, tokens_in, tokens_out, latency_ms, created_at)
            VALUES (:hash, 'запрос', 'RAG', 'ANSWERED', FALSE, 10, 5, 100, :at) RETURNING id
                """.trimIndent(),
            ).param("hash", userHash)
            .param("at", Timestamp.from(t0))
            .query(Long::class.java)
            .single()
    }
}
