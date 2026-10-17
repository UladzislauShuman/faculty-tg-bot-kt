package shuman.bsu.famcs.rag.testsupport

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import shuman.bsu.famcs.rag.common.TokenEstimator
import shuman.bsu.famcs.rag.core.Alerts
import shuman.bsu.famcs.rag.llm.LlmProperties
import shuman.bsu.famcs.rag.llm.LlmProvider
import shuman.bsu.famcs.rag.llm.LlmRole
import shuman.bsu.famcs.rag.llm.LlmUsageListener
import shuman.bsu.famcs.rag.llm.ManagedProvider
import shuman.bsu.famcs.rag.llm.ProviderCall
import shuman.bsu.famcs.rag.llm.ProviderReply
import shuman.bsu.famcs.rag.llm.ReplyValidator
import shuman.bsu.famcs.rag.llm.ResilientLlmClient
import shuman.bsu.famcs.rag.llm.TokenUsage
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Фейковый провайдер: поведение задаётся лямбдой на каждый вызов, все вызовы записываются.
 * Позволяет проверять цепочку, не обращаясь к настоящим моделям (они стоят денег и нестабильны).
 */
class FakeLlmProvider(
    override val name: String,
    private val handler: suspend (ProviderCall, Int) -> ProviderReply,
) : LlmProvider {
    val calls = CopyOnWriteArrayList<ProviderCall>()

    override suspend fun chat(call: ProviderCall): ProviderReply {
        calls += call
        return handler(call, calls.size)
    }

    companion object {
        fun replying(
            name: String,
            text: String,
            usage: TokenUsage? = TokenUsage(inputTokens = 10, outputTokens = 5),
        ) = FakeLlmProvider(name) { call, _ -> ProviderReply(text = text, usage = usage, model = call.model) }
    }
}

/** Часы, которые двигаются только по команде теста. */
class MutableClock(
    start: Instant = Instant.parse("2026-10-08T10:00:00Z"),
) : Clock() {
    private var now: Instant = start

    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** Настройки LLM для тестов: числа подобраны так, чтобы тесты были быстрыми и предсказуемыми. */
object TestLlm {
    val validation = LlmProperties.ReplyValidationProperties(minChars = 2, loopWindowChars = 60, loopRepeats = 5)

    val breaker = LlmProperties.BreakerProperties(
        windowSeconds = 60,
        minimumCalls = 5,
        failureRatePercent = 50,
        openStateWait = Duration.ofSeconds(60),
        halfOpenProbeCalls = 2,
    )

    val retry = LlmProperties.RetryProperties(
        retriesOnPrimary = 1,
        retriesOnFallback = 0,
        backoff = Duration.ofMillis(100),
        jitter = 0.0,
    )

    fun role(
        vararg chain: Pair<String, String>,
        timeout: Duration = Duration.ofSeconds(30),
    ) = LlmProperties.RoleProperties(
        temperature = 0.3,
        topP = 0.9,
        maxOutputTokens = 256,
        callTimeout = timeout,
        chain = chain.map { (provider, model) -> LlmProperties.ChainLink(provider = provider, model = model) },
    )

    fun managed(
        provider: LlmProvider,
        permits: Int = 4,
        clock: Clock = MutableClock(),
    ) = ManagedProvider(
        provider = provider,
        maxConcurrentCalls = permits,
        breaker = ManagedProvider.newBreaker(provider.name, breaker),
        validator = ReplyValidator(validation),
        clock = clock,
    )

    /** Клиент с одной ролью GENERATOR и заданной цепочкой провайдеров. */
    fun client(
        providers: List<ManagedProvider>,
        roleSettings: LlmProperties.RoleProperties,
        listeners: List<LlmUsageListener> = emptyList(),
        retry: LlmProperties.RetryProperties = this.retry,
        alerts: Alerts = Alerts(RecordingNotifier(), MutableClock(), Duration.ofHours(1)),
    ): ResilientLlmClient {
        val chain = roleSettings.chain.map { link ->
            ResilientLlmClient.ChainStep(provider = providers.first { it.name == link.provider }, model = link.model)
        }
        return ResilientLlmClient(
            roles = mapOf(LlmRole.GENERATOR to ResilientLlmClient.RoleRuntime(roleSettings, chain)),
            retry = retry,
            quotaPause = Duration.ofHours(1),
            tokenEstimator = TokenEstimator(charsPerToken = 3.0),
            usageListeners = listeners,
            meters = SimpleMeterRegistry(),
            alerts = alerts,
        )
    }
}
