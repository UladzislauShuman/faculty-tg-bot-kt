package shuman.bsu.famcs.rag.llm.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.llm.exception.LlmAuthenticationException
import shuman.bsu.famcs.rag.llm.exception.LlmQuotaExhaustedException
import shuman.bsu.famcs.rag.llm.exception.LlmRateLimitException
import shuman.bsu.famcs.rag.llm.exception.LlmRequestRejectedException
import shuman.bsu.famcs.rag.llm.exception.LlmServerException
import shuman.bsu.famcs.rag.llm.exception.LlmTimeoutException
import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.model.ProviderReply
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import shuman.bsu.famcs.rag.platform.notification.Alerts
import shuman.bsu.famcs.rag.testsupport.FakeLlmProvider
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.RecordingNotifier
import shuman.bsu.famcs.rag.testsupport.TestLlm
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ResilientLlmClientTest {
    private val question = LlmRequest(LlmRole.GENERATOR, listOf(LlmMessage.user("Как подать документы?")))

    @Test
    fun `успешный вызов возвращает ответ основного провайдера и его расход токенов`() = runTest {
        val primary = FakeLlmProvider.replying("primary", "Подайте заявление в деканат.", TokenUsage(120, 30))
        val client = TestLlm.client(listOf(TestLlm.managed(primary)), TestLlm.role("primary" to "model-a"))

        val reply = client.complete(question)

        assertEquals("Подайте заявление в деканат.", reply.text)
        assertEquals("primary", reply.provider)
        assertEquals(TokenUsage(120, 30), reply.usage)
        assertEquals("model-a", primary.calls.single().model)
    }

    @Test
    fun `параметры генерации берутся из настроек роли`() = runTest {
        val primary = FakeLlmProvider.replying("primary", "Ответ")
        val client = TestLlm.client(listOf(TestLlm.managed(primary)), TestLlm.role("primary" to "m"))

        client.complete(question)

        val call = primary.calls.single()
        assertEquals(0.3, call.temperature)
        assertEquals(0.9, call.topP)
        assertEquals(256, call.maxOutputTokens)
    }

    @Test
    fun `временный сбой повторяется у основного провайдера и вызов успешен`() = runTest {
        val primary = FakeLlmProvider("primary") { call, n ->
            if (n == 1) throw LlmServerException("503") else ProviderReply("Ответ", null, call.model)
        }
        val client = TestLlm.client(listOf(TestLlm.managed(primary)), TestLlm.role("primary" to "m"))

        val reply = client.complete(question)

        assertEquals("Ответ", reply.text)
        assertEquals(2, primary.calls.size)
    }

    @Test
    fun `после исчерпания повторов вызывается резервный провайдер`() = runTest {
        val primary = FakeLlmProvider("primary") { _, _ -> throw LlmServerException("503") }
        val fallback = FakeLlmProvider.replying("fallback", "Ответ резерва")
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary), TestLlm.managed(fallback)),
            TestLlm.role("primary" to "m1", "fallback" to "m2"),
        )

        val reply = client.complete(question)

        assertEquals("fallback", reply.provider)
        assertEquals(2, primary.calls.size, "основной: первая попытка и один повтор")
        assertEquals(1, fallback.calls.size)
    }

    @Test
    fun `ограничение частоты запросов не повторяется, сразу идёт резерв`() = runTest {
        val primary = FakeLlmProvider("primary") { _, _ -> throw LlmRateLimitException("429") }
        val fallback = FakeLlmProvider.replying("fallback", "Ответ резерва")
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary), TestLlm.managed(fallback)),
            TestLlm.role("primary" to "m1", "fallback" to "m2"),
        )

        client.complete(question)

        assertEquals(1, primary.calls.size)
        assertEquals(1, fallback.calls.size)
    }

    @Test
    fun `пустой ответ модели считается сбоем и повторяется`() = runTest {
        val primary = FakeLlmProvider("primary") { call, n ->
            ProviderReply(text = if (n == 1) "   " else "Нормальный ответ", usage = null, model = call.model)
        }
        val client = TestLlm.client(listOf(TestLlm.managed(primary)), TestLlm.role("primary" to "m"))

        val reply = client.complete(question)

        assertEquals("Нормальный ответ", reply.text)
        assertEquals(2, primary.calls.size)
    }

    @Test
    fun `если отказали все провайдеры, бросается LlmUnavailableException`() = runTest {
        val primary = FakeLlmProvider("primary") { _, _ -> throw LlmServerException("503") }
        val fallback = FakeLlmProvider("fallback") { _, _ -> throw LlmServerException("503") }
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary), TestLlm.managed(fallback)),
            TestLlm.role("primary" to "m1", "fallback" to "m2"),
        )

        assertFailsWith<LlmUnavailableException> { client.complete(question) }
    }

    @Test
    fun `отклонённый запрос не повторяется и не считается сбоем провайдера`() = runTest {
        val primary = FakeLlmProvider("primary") { _, _ -> throw LlmRequestRejectedException("неверная модель") }
        val client = TestLlm.client(listOf(TestLlm.managed(primary)), TestLlm.role("primary" to "m"))

        assertFailsWith<LlmUnavailableException> { client.complete(question) }
        assertEquals(1, primary.calls.size)
    }

    @Test
    fun `таймаут вызова превращается в LlmTimeoutException и уходит к повтору и резерву`() = runTest {
        val primary = FakeLlmProvider("primary") { _, _ ->
            delay(60.seconds)
            ProviderReply("поздно", null, "m")
        }
        val fallback = FakeLlmProvider.replying("fallback", "Ответ резерва")
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary), TestLlm.managed(fallback)),
            TestLlm.role("primary" to "m1", "fallback" to "m2", timeout = Duration.ofSeconds(5)),
        )

        val reply = client.complete(question)

        assertEquals("fallback", reply.provider)
        assertEquals(2, primary.calls.size)
    }

    @Test
    fun `исчерпанная квота откладывает провайдера, следующие вызовы идут сразу к резерву`() = runTest {
        val clock = MutableClock()
        val primary = FakeLlmProvider("primary") { _, _ -> throw LlmQuotaExhaustedException("402") }
        val fallback = FakeLlmProvider.replying("fallback", "Ответ резерва")
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary, clock = clock), TestLlm.managed(fallback, clock = clock)),
            TestLlm.role("primary" to "m1", "fallback" to "m2"),
        )

        client.complete(question)
        client.complete(question)

        assertEquals(1, primary.calls.size, "после квоты основной не вызывается")
        assertEquals(2, fallback.calls.size)

        clock.advance(Duration.ofHours(2))
        client.complete(question)
        assertEquals(2, primary.calls.size, "после паузы основной снова пробуется")
    }

    @Test
    fun `частые сбои размыкают ограничитель, провайдер пропускается`() = runTest {
        val primary = FakeLlmProvider("primary") { _, _ -> throw LlmServerException("503") }
        val fallback = FakeLlmProvider.replying("fallback", "Ответ резерва")
        val noRetry = TestLlm.retry.copy(retriesOnPrimary = 0)
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary), TestLlm.managed(fallback)),
            TestLlm.role("primary" to "m1", "fallback" to "m2"),
            retry = noRetry,
        )

        repeat(5) { client.complete(question) }
        val callsBefore = primary.calls.size
        client.complete(question)

        assertEquals(5, callsBefore)
        assertEquals(callsBefore, primary.calls.size, "ограничитель разомкнут: основной не вызывается")
    }

    @Test
    fun `одновременных вызовов к провайдеру не больше его квоты`() = runTest {
        val current = AtomicInteger()
        val peak = AtomicInteger()
        val primary = FakeLlmProvider("primary") { call, _ ->
            peak.accumulateAndGet(current.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(1.seconds)
            current.decrementAndGet()
            ProviderReply("Ответ", null, call.model)
        }
        val client = TestLlm.client(listOf(TestLlm.managed(primary, permits = 2)), TestLlm.role("primary" to "m"))

        (1..6).map { async { client.complete(question) } }.awaitAll()

        assertEquals(2, peak.get())
    }

    @Test
    fun `расход токенов уходит слушателям, а при отсутствии у провайдера оценивается по длине`() = runTest {
        val reported = mutableListOf<TokenUsage>()
        val primary = FakeLlmProvider.replying("primary", "Короткий ответ модели", usage = null)
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary)),
            TestLlm.role("primary" to "m"),
            listeners = listOf(LlmUsageListener { _, _, _, usage -> reported += usage }),
        )

        client.complete(question)

        val usage = reported.single()
        assertTrue(usage.inputTokens > 0 && usage.outputTokens > 0, "оценка не нулевая: $usage")
    }

    @Test
    fun `исчерпанная квота и отказ ключа оповещают администратора, повтор не чаще раза в час`() = runTest {
        val notifier = RecordingNotifier()
        val clock = MutableClock()
        val alerts = Alerts(notifier, clock, Duration.ofHours(1))
        val primary = FakeLlmProvider("primary") { _, _ -> throw LlmQuotaExhaustedException("402") }
        val reserve = FakeLlmProvider("reserve") { _, _ -> throw LlmAuthenticationException("401") }
        val client = TestLlm.client(
            listOf(TestLlm.managed(primary), TestLlm.managed(reserve)),
            TestLlm.role("primary" to "a", "reserve" to "b"),
            alerts = alerts,
        )

        repeat(2) { assertFailsWith<LlmUnavailableException> { client.complete(question) } }

        assertEquals(2, notifier.messages.size, "по одному оповещению на квоту и на ключ")
        assertTrue(notifier.messages.any { "primary" in it && "квота" in it })
        assertTrue(notifier.messages.any { "reserve" in it && "ключ" in it })
    }

    @Test
    fun `дедлайн внешней корутины не принимается за таймаут провайдера`() = runTest {
        val slow = FakeLlmProvider("slow") { call, _ ->
            delay(100.seconds)
            ProviderReply("поздно", null, call.model)
        }
        val client = TestLlm.client(listOf(TestLlm.managed(slow)), TestLlm.role("slow" to "m", timeout = Duration.ofSeconds(60)))

        val result = kotlinx.coroutines.withTimeoutOrNull(5.seconds) { client.complete(question) }

        assertEquals(null, result, "сработал внешний дедлайн 5 с, а не таймаут провайдера в 60 с")
        assertEquals(1, slow.calls.size, "повтора после чужого таймаута не было")
    }
}
