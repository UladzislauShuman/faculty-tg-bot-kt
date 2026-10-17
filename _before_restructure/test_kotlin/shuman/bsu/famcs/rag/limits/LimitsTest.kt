package shuman.bsu.famcs.rag.limits

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.llm.LlmRole
import shuman.bsu.famcs.rag.llm.TokenUsage
import shuman.bsu.famcs.rag.testsupport.InMemoryUsageStore
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.RecordingNotifier
import shuman.bsu.famcs.rag.testsupport.TestCore
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LimitsTest {
    private val user = TelegramId(1)
    private val clock = MutableClock()

    // --- Минутное окно ---

    @Test
    fun `в минутное окно помещается ровно N вопросов, следующий получает время ожидания`() {
        val window = MinuteWindow(perMinute = 3, window = Duration.ofMinutes(1))
        val start = clock.instant()

        repeat(3) { assertNull(window.tryAcquire(user, start.plusSeconds(it * 10L))) }
        val wait = window.tryAcquire(user, start.plusSeconds(30))

        assertEquals(Duration.ofSeconds(30), wait, "освободится место, занятое первым вопросом")
        assertNull(window.tryAcquire(user, start.plusSeconds(60)), "через минуту после первого вопроса место свободно")
    }

    @Test
    fun `отказанный вопрос место в окне не занимает`() {
        val window = MinuteWindow(perMinute = 1, window = Duration.ofMinutes(1))
        val start = clock.instant()
        assertNull(window.tryAcquire(user, start))

        repeat(10) { assertTrue(window.tryAcquire(user, start.plusSeconds(1)) != null) }

        assertNull(window.tryAcquire(user, start.plusSeconds(60)))
    }

    @Test
    fun `окна разных пользователей независимы, а простаивающие забываются`() {
        val window = MinuteWindow(perMinute = 1, window = Duration.ofMinutes(1))
        val start = clock.instant()

        assertNull(window.tryAcquire(TelegramId(1), start))
        assertNull(window.tryAcquire(TelegramId(2), start))
        window.evictIdle(start.plusSeconds(120))

        assertNull(window.tryAcquire(TelegramId(1), start.plusSeconds(121)))
    }

    // --- Один вопрос за раз ---

    @Test
    fun `место в обработке занимается один раз и освобождается`() {
        val guard = InFlightGuard()

        assertTrue(guard.tryEnter(user))
        assertTrue(!guard.tryEnter(user))
        assertTrue(guard.tryEnter(TelegramId(2)))
        guard.leave(user)
        assertTrue(guard.tryEnter(user))
    }

    // --- Очередь ---

    @Test
    fun `вопросы сверх числа мест ждут, сверх очереди получают отказ сразу`() = runTest {
        val gate = AdmissionGate(maxConcurrent = 1, maxWaiting = 1, maxWait = Duration.ofSeconds(60))
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.withSlot {
                release.await()
                "первый"
            }
        }
        runCurrent()
        val second = async { gate.withSlot { "второй" } }
        runCurrent()

        assertEquals(1, gate.waitingCount)
        assertFailsWith<OverloadedException> { gate.withSlot { "третий" } }
        release.complete(Unit)

        assertEquals("первый", first.await())
        assertEquals("второй", second.await())
        assertEquals(0, gate.waitingCount)
    }

    @Test
    fun `вопрос, не дождавшийся места за отведённое время, получает отказ`() = runTest {
        val gate = AdmissionGate(maxConcurrent = 1, maxWaiting = 5, maxWait = Duration.ofSeconds(60))
        val first = async { gate.withSlot { delay(1_000_000) } }
        runCurrent()
        val second = async { runCatching { gate.withSlot { "поздно" } } }
        runCurrent()

        advanceTimeBy(61_000)

        assertTrue(second.await().exceptionOrNull() is OverloadedException)
        assertEquals(0, gate.waitingCount)
        first.cancel()
    }

    @Test
    fun `место освобождается даже если работа завершилась ошибкой`() = runTest {
        val gate = AdmissionGate(maxConcurrent = 1, maxWaiting = 0, maxWait = Duration.ofSeconds(1))

        assertFailsWith<IllegalStateException> { gate.withSlot { error("сбой") } }

        assertEquals("ок", gate.withSlot { "ок" })
    }

    // --- Предохранитель по деньгам ---

    private fun guard(
        usage: InMemoryUsageStore = InMemoryUsageStore(),
        notifier: RecordingNotifier = RecordingNotifier(),
    ) = BudgetGuard(usage, TestCore.limits.system, clock, notifier)

    @Test
    fun `режим меняется по расходу токенов - обычный, экономия, исчерпан`() = runTest {
        val usage = InMemoryUsageStore()
        val guard = guard(usage)
        val today = LocalDate.now(clock)

        assertEquals(BudgetMode.NORMAL, guard.mode())
        usage.systemTokens[today] = 6_999
        assertEquals(BudgetMode.NORMAL, guard.mode())
        usage.systemTokens[today] = 7_000
        assertEquals(BudgetMode.ECONOMY, guard.mode())
        usage.systemTokens[today] = 10_000
        assertEquals(BudgetMode.EXHAUSTED, guard.mode())
    }

    @Test
    fun `потолок вопросов системы тоже исчерпывает бюджет`() = runTest {
        val usage = InMemoryUsageStore()
        usage.systemMessages[LocalDate.now(clock)] = 500

        assertEquals(BudgetMode.EXHAUSTED, guard(usage).mode())
    }

    @Test
    fun `расход токенов попадает в счётчик системы, предупреждение уходит один раз на уровень`() = runTest {
        val usage = InMemoryUsageStore()
        val notifier = RecordingNotifier()
        val guard = guard(usage, notifier)

        suspend fun spend(tokens: Int) = guard.onUsage(LlmRole.GENERATOR, "p", "m", TokenUsage(tokens, 0))

        spend(7_000)
        assertEquals(0, notifier.messages.size, "ниже порога предупреждения")
        spend(1_500)
        assertEquals(1, notifier.messages.size)
        spend(100)
        assertEquals(1, notifier.messages.size, "тот же уровень повторно не отправляется")
        spend(1_500)
        assertEquals(2, notifier.messages.size, "потолок - новый уровень")
        assertTrue("исчерпан" in notifier.messages.last())
        assertEquals(10_100L, usage.systemTokens[LocalDate.now(clock)])
    }

    @Test
    fun `на следующий день предупреждения отправляются снова`() = runTest {
        val notifier = RecordingNotifier()
        val guard = guard(notifier = notifier)

        guard.onUsage(LlmRole.GENERATOR, "p", "m", TokenUsage(8_500, 0))
        clock.advance(Duration.ofDays(1))
        guard.onUsage(LlmRole.GENERATOR, "p", "m", TokenUsage(8_500, 0))

        assertEquals(2, notifier.messages.size)
    }
}
