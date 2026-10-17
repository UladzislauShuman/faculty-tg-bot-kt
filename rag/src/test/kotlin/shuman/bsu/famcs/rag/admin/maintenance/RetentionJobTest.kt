package shuman.bsu.famcs.rag.admin.maintenance

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.admin.maintenance.repository.RetentionRepository
import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.assistant.limit.service.MinuteWindow
import shuman.bsu.famcs.rag.platform.configuration.properties.RetentionProperties
import shuman.bsu.famcs.rag.testsupport.InMemoryConversationRepository
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.TestRag
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

class RetentionJobTest {
    private val clock = MutableClock()

    private class RecordingStore(private val failOnAnswerLog: Boolean = false) : RetentionRepository {
        val calls = mutableListOf<String>()
        var answerLogCutoff: Instant? = null
        var usageDay: LocalDate? = null
        var sessionsCutoff: Instant? = null

        override suspend fun deleteAnswerLogOlderThan(cutoff: Instant): Int {
            calls += "answer_log"
            if (failOnAnswerLog) error("тестовый сбой")
            answerLogCutoff = cutoff
            return 1
        }

        override suspend fun deleteUsageBefore(day: LocalDate): Int {
            calls += "usage"
            usageDay = day
            return 1
        }

        override suspend fun deleteEmptySessionsOlderThan(cutoff: Instant): Int {
            calls += "sessions"
            sessionsCutoff = cutoff
            return 1
        }
    }

    private class RecordingProcessed : ProcessedUpdateRepository {
        var cutoff: Instant? = null

        override suspend fun registerIfNew(
            updateId: Long,
            at: Instant,
        ) = true

        override suspend fun purgeOlderThan(cutoff: Instant): Int {
            this.cutoff = cutoff
            return 0
        }
    }

    private val retention = RetentionProperties(
        messagesDays = 90,
        answerLogDays = 60,
        usageDays = 30,
        cron = "0 30 4 * * *",
        memorySweepInterval = Duration.ofHours(1),
    )

    private fun job(
        store: RetentionRepository,
        processed: ProcessedUpdateRepository,
        conversations: InMemoryConversationRepository = InMemoryConversationRepository(),
    ) = RetentionJob(
        conversations = conversations,
        processed = processed,
        store = store,
        minuteWindow = MinuteWindow(perMinute = 5, window = Duration.ofMinutes(1)),
        retention = retention,
        updates = TestRag.limits.updates,
        clock = clock,
    )

    @Test
    fun `каждый вид данных очищается по своему сроку хранения`() = runTest {
        val store = RecordingStore()
        val processed = RecordingProcessed()

        job(store, processed).cleanUp()

        val now = clock.instant()
        assertEquals(now.minus(Duration.ofDays(60)), store.answerLogCutoff)
        assertEquals(now.minus(Duration.ofDays(90)), store.sessionsCutoff)
        assertEquals(LocalDate.now(clock).minusDays(30), store.usageDay)
        assertEquals(now.minus(TestRag.limits.updates.retention), processed.cutoff)
    }

    @Test
    fun `сбой одного шага не мешает остальным`() = runTest {
        val store = RecordingStore(failOnAnswerLog = true)
        val processed = RecordingProcessed()

        job(store, processed).cleanUp()

        assertEquals(listOf("sessions", "answer_log", "usage").sorted(), store.calls.sorted())
        assertEquals(clock.instant().minus(TestRag.limits.updates.retention), processed.cutoff, "последний шаг выполнен")
    }
}
