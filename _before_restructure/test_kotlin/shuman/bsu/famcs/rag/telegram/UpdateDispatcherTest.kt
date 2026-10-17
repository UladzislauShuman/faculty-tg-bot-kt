package shuman.bsu.famcs.rag.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDispatcherTest {
    @Test
    fun `сверх лимита обновление отбрасывается и считается`() = runTest {
        val dropped = AtomicInteger()
        val dispatcher = UpdateDispatcher(this, maxConcurrent = 2, gracePeriod = 10.seconds, onDropped = { dropped.incrementAndGet() })
        val release = CompletableDeferred<Unit>()

        assertTrue(dispatcher.submit("a") { release.await() })
        assertTrue(dispatcher.submit("b") { release.await() })
        runCurrent()
        assertFalse(dispatcher.submit("c") { }, "третье обновление не помещается")
        assertEquals(1, dropped.get())

        release.complete(Unit)
        runCurrent()
        assertTrue(dispatcher.submit("d") { }, "места освободились")
    }

    @Test
    fun `место освобождается даже если обработчик упал`() = runTest {
        val dispatcher = UpdateDispatcher(this, maxConcurrent = 1, gracePeriod = 10.seconds)

        dispatcher.submit("упадёт") { error("сбой обработчика") }
        runCurrent()

        assertTrue(dispatcher.submit("следующий") { })
    }

    @Test
    fun `при остановке принятые вопросы дожидаются, новые не принимаются`() = runTest {
        val dispatcher = UpdateDispatcher(this, maxConcurrent = 5, gracePeriod = 30.seconds)
        val finished = AtomicInteger()
        dispatcher.submit("долгий") {
            delay(10_000)
            finished.incrementAndGet()
        }
        runCurrent()

        val draining = async { dispatcher.drain() }
        runCurrent()
        assertFalse(dispatcher.submit("новый") { }, "после начала остановки приём закрыт")
        advanceTimeBy(10_001)
        draining.await()

        assertEquals(1, finished.get(), "принятый вопрос доработал до конца")
    }

    @Test
    fun `обработчики, не уложившиеся в срок ожидания, отменяются`() = runTest {
        val dispatcher = UpdateDispatcher(this, maxConcurrent = 5, gracePeriod = 5.seconds)
        val finished = AtomicInteger()
        dispatcher.submit("зависший") {
            delay(1_000_000)
            finished.incrementAndGet()
        }
        runCurrent()

        val draining = async { dispatcher.drain() }
        advanceTimeBy(5_001)
        draining.await()
        advanceTimeBy(2_000_000)

        assertEquals(0, finished.get())
    }
}
