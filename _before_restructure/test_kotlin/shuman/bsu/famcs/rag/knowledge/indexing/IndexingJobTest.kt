package shuman.bsu.famcs.rag.knowledge.indexing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.app.ApplicationScope
import shuman.bsu.famcs.rag.testsupport.RecordingNotifier
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexingJobTest {
    private val scope = ApplicationScope(Duration.ofSeconds(1))
    private val notifier = RecordingNotifier()

    @AfterEach
    fun stop() = scope.destroy()

    private fun report(status: IndexRunStatus) = IndexReport(1, IndexMode.INCREMENTAL, status, IndexCounters(10, 2, 0, 0), null)

    private suspend fun awaitNotifications(count: Int) = withTimeout(5_000) {
        while (notifier.messages.size < count) delay(10)
    }

    @Test
    fun `вторая индексация не запускается, пока идёт первая`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val job = IndexingJob({ release.await().let { report(IndexRunStatus.SUCCEEDED) } }, scope, notifier)

        assertTrue(job.start(IndexMode.INCREMENTAL, reportSuccess = true))
        assertFalse(job.start(IndexMode.INCREMENTAL), "предыдущая ещё идёт")

        release.complete(Unit)
        awaitNotifications(1)
        withTimeout(5_000) { while (!job.start(IndexMode.INCREMENTAL, reportSuccess = false)) delay(10) }
    }

    @Test
    fun `ночной успех не шумит, неудача сообщается всегда`() = runBlocking<Unit> {
        val job = IndexingJob({ report(IndexRunStatus.SUCCEEDED) }, scope, notifier)
        job.start(IndexMode.INCREMENTAL, reportSuccess = false)
        delay(200)
        assertEquals(0, notifier.messages.size)

        val failing = IndexingJob({ report(IndexRunStatus.ABORTED) }, scope, notifier)
        withTimeout(5_000) { while (!failing.start(IndexMode.INCREMENTAL, reportSuccess = false)) delay(10) }
        awaitNotifications(1)

        assertTrue("ABORTED" in notifier.messages.single())
    }

    @Test
    fun `запуск вручную сообщает и об успехе`() = runBlocking<Unit> {
        val job = IndexingJob({ report(IndexRunStatus.SUCCEEDED) }, scope, notifier)

        job.start(IndexMode.INCREMENTAL, reportSuccess = true)
        awaitNotifications(1)

        assertTrue("SUCCEEDED" in notifier.messages.single())
    }
}
