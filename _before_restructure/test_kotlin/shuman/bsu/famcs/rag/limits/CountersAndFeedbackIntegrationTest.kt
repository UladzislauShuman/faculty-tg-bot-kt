package shuman.bsu.famcs.rag.limits

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UserHasher
import shuman.bsu.famcs.rag.core.AnswerStatus
import shuman.bsu.famcs.rag.dialog.JdbcUserDirectory
import shuman.bsu.famcs.rag.feedback.AnswerLogEntry
import shuman.bsu.famcs.rag.feedback.JdbcAnswerLog
import shuman.bsu.famcs.rag.feedback.JdbcFeedbackService
import shuman.bsu.famcs.rag.intent.Intent
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.TestPostgres
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Счётчики, дедупликация обновлений, журнал ответов и оценки на настоящем PostgreSQL (нужен Docker).
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CountersAndFeedbackIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = TestPostgres.container()
    }

    private lateinit var db: Database
    private lateinit var usage: JdbcUsageStore
    private lateinit var processed: JdbcProcessedUpdates
    private lateinit var answerLog: JdbcAnswerLog
    private lateinit var feedback: JdbcFeedbackService
    private lateinit var users: JdbcUserDirectory
    private val clock = MutableClock()
    private val hasher = UserHasher("integration-secret-0123456789", length = 16)
    private val day = LocalDate.of(2026, 10, 8)
    private val nextUser = AtomicLong(5000)

    @BeforeAll
    fun connect() {
        db = TestPostgres.database(postgres)
        usage = JdbcUsageStore(db, hasher)
        processed = JdbcProcessedUpdates(db)
        answerLog = JdbcAnswerLog(db)
        feedback = JdbcFeedbackService(db, hasher, clock)
        users = JdbcUserDirectory(db, hasher)
    }

    private suspend fun newUser() = TelegramId(nextUser.incrementAndGet()).also { users.visit(it, clock.instant()) }

    private fun entry(user: TelegramId) = AnswerLogEntry(
        messageId = null,
        userHash = hasher.hash(user),
        query = "когда практика",
        route = Intent.RAG,
        status = AnswerStatus.ANSWERED,
        noInfo = false,
        sources = listOf("https://a", "https://b"),
        provider = "yandex",
        model = "lite",
        tokensIn = 100,
        tokensOut = 20,
        latencyMs = 1500,
        createdAt = clock.instant(),
    )

    // --- Счётчики ---

    @Test
    fun `вопросы и токены накапливаются у пользователя и у системы`() = runBlocking<Unit> {
        val user = newUser()
        val before = usage.system(day)

        usage.recordMessage(user, day, tokens = 300)
        usage.recordMessage(user, day, tokens = 200)

        assertEquals(2, usage.userMessages(user, day))
        assertEquals(0, usage.userMessages(user, day.plusDays(1)), "сутки считаются отдельно")
        assertEquals(before.messages + 2, usage.system(day).messages)
    }

    @Test
    fun `токены системы накапливаются атомарно при одновременных вызовах`() = runBlocking<Unit> {
        val start = usage.system(day.plusDays(10)).tokens

        (1..20).map { async { usage.addSystemTokens(day.plusDays(10), 50) } }.awaitAll()

        assertEquals(start + 1000, usage.system(day.plusDays(10)).tokens)
    }

    @Test
    fun `суточный счётчик переживает забвение - лимит нельзя обойти повторной регистрацией`() = runBlocking<Unit> {
        val user = newUser()
        repeat(3) { usage.recordMessage(user, day, tokens = 10) }

        users.forget(user)
        users.visit(user, clock.instant())

        assertEquals(3, usage.userMessages(user, day))
    }

    // --- Дедупликация обновлений ---

    @Test
    fun `номер обновления принимается один раз, даже при одновременной доставке`() = runBlocking<Unit> {
        val results = (1..8).map { async { processed.registerIfNew(900_001, clock.instant()) } }.awaitAll()

        assertEquals(1, results.count { it })
        assertFalse(processed.registerIfNew(900_001, clock.instant()))
    }

    @Test
    fun `старые номера обновлений очищаются, свежие остаются`() = runBlocking<Unit> {
        processed.registerIfNew(900_010, clock.instant().minus(Duration.ofDays(8)))
        processed.registerIfNew(900_011, clock.instant())

        val removed = processed.purgeOlderThan(clock.instant().minus(Duration.ofDays(7)))

        assertTrue(removed >= 1)
        assertTrue(processed.registerIfNew(900_010, clock.instant()), "старый номер забыт и принимается снова")
        assertFalse(processed.registerIfNew(900_011, clock.instant()), "свежий номер помнится")
    }

    // --- Журнал и оценки ---

    @Test
    fun `запись журнала сохраняет все поля, а адреса источников склеиваются через пробел`() = runBlocking<Unit> {
        val user = newUser()

        val id = answerLog.append(entry(user))

        val row = db.query { jdbc ->
            jdbc
                .sql("SELECT route, status, doc_ids, provider, tokens_in, latency_ms FROM answer_log WHERE id = :id")
                .param("id", id.value)
                .query {
                    rs,
                    _,
                    ->
                    listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6))
                }.single()
        }
        assertEquals(listOf("RAG", "ANSWERED", "https://a https://b", "yandex", 100, 1500), row)
    }

    @Test
    fun `оценка сохраняется, повторная перезаписывает прежнюю`() = runBlocking<Unit> {
        val user = newUser()
        val id = answerLog.append(entry(user))

        assertTrue(feedback.rate(id, user, rating = 2, reason = "неточно"))
        assertTrue(feedback.rate(id, user, rating = 5))

        val rows = db.query { jdbc ->
            jdbc
                .sql("SELECT rating, reason FROM feedback WHERE answer_id = :id")
                .param("id", id.value)
                .query { rs, _ -> rs.getInt(1) to rs.getString(2) }
                .list()
        }
        assertEquals(listOf(5 to null), rows)
    }

    @Test
    fun `чужой ответ оценить нельзя`() = runBlocking<Unit> {
        val owner = newUser()
        val stranger = newUser()
        val id = answerLog.append(entry(owner))

        assertFalse(feedback.rate(id, stranger, rating = 1))
        assertFalse(feedback.rate(AnswerId(999_999_999), owner, rating = 1), "несуществующий ответ")
    }

    @Test
    fun `оценка вне диапазона отвергается до обращения к базе`() = runBlocking<Unit> {
        val user = newUser()
        val id = answerLog.append(entry(user))

        assertFailsWith<IllegalArgumentException> { feedback.rate(id, user, rating = 6) }
        assertFailsWith<IllegalArgumentException> { feedback.rate(id, user, rating = 0) }
    }

    @Test
    fun `после забвения оценки удалены, а журнал обезличен`() = runBlocking<Unit> {
        val user = newUser()
        val id = answerLog.append(entry(user))
        feedback.rate(id, user, rating = 4)

        users.forget(user)

        val remaining = db.query { jdbc ->
            jdbc
                .sql("SELECT user_hash, rewritten_query FROM answer_log WHERE id = :id")
                .param("id", id.value)
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .single()
        }
        assertEquals(null to null, remaining)
        assertEquals(
            0,
            db.query {
                it
                    .sql("SELECT count(*) FROM feedback WHERE answer_id = :id")
                    .param("id", id.value)
                    .query(Int::class.java)
                    .single()
            },
        )
    }
}
