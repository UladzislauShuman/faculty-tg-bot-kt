package shuman.bsu.famcs.rag.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import shuman.bsu.famcs.rag.common.TokenEstimator
import shuman.bsu.famcs.rag.core.Alerts
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * Клиент LLM для ядра: выбирает цепочку по роли и проходит её до первого успеха.
 *
 * Порядок: основной провайдер → один повтор при временном сбое (5xx, таймаут, плохой ответ) → резервный → отказ.
 * Каждый вызов считается в метриках, расход токенов уходит слушателям (дневные счётчики и бюджет).
 */
class ResilientLlmClient(
    private val roles: Map<LlmRole, RoleRuntime>,
    private val retry: LlmProperties.RetryProperties,
    private val quotaPause: Duration,
    private val tokenEstimator: TokenEstimator,
    private val usageListeners: List<LlmUsageListener>,
    private val meters: MeterRegistry,
    private val alerts: Alerts,
    private val random: Random = Random.Default,
) : LlmClient {
    /** Готовая к работе роль: параметры генерации и провайдеры цепочки уже под защитой. */
    class RoleRuntime(
        val settings: LlmProperties.RoleProperties,
        val chain: List<ChainStep>,
    )

    class ChainStep(
        val provider: ManagedProvider,
        val model: String,
    )

    override suspend fun complete(request: LlmRequest): LlmReply {
        val runtime = roles[request.role] ?: error("Роль ${request.role} не настроена в bot.llm.roles")
        val failures = mutableListOf<LlmException>()

        for ((index, step) in runtime.chain.withIndex()) {
            if (step.provider.isPaused()) {
                log.warn { "Провайдер '${step.provider.name}' отложен (квота), роль ${request.role}: пропускаем" }
                continue
            }
            val attempts = 1 + if (index == 0) retry.retriesOnPrimary else retry.retriesOnFallback
            for (attempt in 1..attempts) {
                val started = TimeSource.Monotonic.markNow()
                try {
                    val providerReply = step.provider.call(callFor(runtime, step, request), runtime.settings.callTimeout)
                    val elapsed = started.elapsedNow()
                    val usage = providerReply.usage ?: estimateUsage(request, providerReply.text)
                    recordSuccess(request.role, step.provider.name, elapsed, usage)
                    // Расход попадает в сборщик текущего сообщения (если он есть в контексте) и в общие счётчики.
                    currentCoroutineContext()[LlmUsageCollector]?.add(usage)
                    notifyUsage(request.role, step.provider.name, providerReply.model, usage)
                    if (index > 0) {
                        log.warn { "Роль ${request.role}: ответил резервный провайдер '${step.provider.name}'" }
                    }
                    return LlmReply(
                        text = providerReply.text,
                        usage = usage,
                        provider = step.provider.name,
                        model = providerReply.model,
                        elapsed = elapsed,
                    )
                } catch (e: LlmException) {
                    failures += e
                    recordFailure(request.role, step.provider.name, e)
                    log.warn { "Роль ${request.role}, провайдер '${step.provider.name}', попытка $attempt из $attempts: ${e.message}" }
                    alertAdmins(step.provider.name, e)
                    if (e is LlmQuotaExhaustedException) step.provider.pauseFor(quotaPause)
                    if (!e.retryable || attempt == attempts) break
                    delay(backoffWithJitter())
                }
            }
        }
        throw LlmUnavailableException(
            "Все провайдеры роли ${request.role} отказали (${failures.size} ошибок)",
            failures.lastOrNull(),
        )
    }

    /** Сбои, которые требуют человека: кончилась квота, не принят ключ, провайдер отключён размыкателем. Повтор оповещения — не чаще раза в час. */
    private suspend fun alertAdmins(
        provider: String,
        error: LlmException,
    ) {
        when (error) {
            is LlmQuotaExhaustedException -> alerts.raise(
                "quota:$provider",
                "У провайдера '$provider' исчерпана квота или деньги. Он отложен на $quotaPause. Пополните счёт или переключите цепочку.",
            )

            is LlmAuthenticationException -> alerts.raise(
                "auth:$provider",
                "Провайдер '$provider' не принял ключ или права. Проверьте ключ API и идентификатор каталога.",
            )

            is LlmProviderUnavailableException -> alerts.raise(
                "breaker:$provider",
                "Провайдер '$provider' отключён размыкателем после серии сбоев. Бот переключился на резерв (если он есть).",
            )

            else -> Unit
        }
    }

    private fun callFor(
        runtime: RoleRuntime,
        step: ChainStep,
        request: LlmRequest,
    ) = ProviderCall(
        model = step.model,
        messages = request.messages,
        temperature = runtime.settings.temperature,
        topP = runtime.settings.topP,
        maxOutputTokens = runtime.settings.maxOutputTokens,
    )

    private fun backoffWithJitter(): kotlin.time.Duration {
        val spread = (random.nextDouble() * 2 - 1) * retry.jitter
        return retry.backoff.toKotlinDuration() * (1.0 + spread)
    }

    /** Провайдер не сообщил токены: оцениваем по длине. Лучше приблизительный учёт, чем никакого. */
    private fun estimateUsage(
        request: LlmRequest,
        replyText: String,
    ) = TokenUsage(
        inputTokens = tokenEstimator.estimate(request.messages.map { it.content }),
        outputTokens = tokenEstimator.estimate(replyText),
    )

    private fun recordSuccess(
        role: LlmRole,
        provider: String,
        elapsed: kotlin.time.Duration,
        usage: TokenUsage,
    ) {
        val tag = role.metricTag()
        meters.counter("llm.calls", "provider", provider, "role", tag, "outcome", "success").increment()
        meters.timer("llm.latency", "provider", provider, "role", tag).record(elapsed.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        meters.counter("llm.tokens", "provider", provider, "role", tag, "direction", "input").increment(usage.inputTokens.toDouble())
        meters.counter("llm.tokens", "provider", provider, "role", tag, "direction", "output").increment(usage.outputTokens.toDouble())
    }

    private fun recordFailure(
        role: LlmRole,
        provider: String,
        error: LlmException,
    ) {
        val outcome = error.javaClass.simpleName
            .removePrefix("Llm")
            .removeSuffix("Exception")
            .lowercase()
        meters.counter("llm.calls", "provider", provider, "role", role.metricTag(), "outcome", outcome).increment()
    }

    private suspend fun notifyUsage(
        role: LlmRole,
        provider: String,
        model: String,
        usage: TokenUsage,
    ) {
        for (listener in usageListeners) {
            try {
                listener.onUsage(role, provider, model, usage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Учёт расхода не должен ломать ответ пользователю.
                log.error(e) { "Не удалось учесть расход токенов (роль $role)" }
            }
        }
    }

    private fun LlmRole.metricTag() = name.lowercase()
}
