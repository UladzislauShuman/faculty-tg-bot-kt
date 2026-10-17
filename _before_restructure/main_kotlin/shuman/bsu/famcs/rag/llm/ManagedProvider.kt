package shuman.bsu.famcs.rag.llm

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.toKotlinDuration

/**
 * Провайдер под защитой: лимит одновременных вызовов, ограничитель частоты ошибок, пауза при исчерпанной квоте.
 *
 * Порядок защит внутри одного вызова: таймаут → ожидание свободного места (bulkhead) → ограничитель → вызов → проверка
 * ответа. Таймаут стоит снаружи всего: ожидание места входит в бюджет вызова, а разрешение bulkhead возвращается даже
 * при таймауте на ожидании.
 */
class ManagedProvider(
    private val provider: LlmProvider,
    maxConcurrentCalls: Int,
    private val breaker: CircuitBreaker,
    private val validator: ReplyValidator,
    private val clock: Clock,
) {
    private val bulkhead = Semaphore(maxConcurrentCalls)
    private val pausedUntil = AtomicReference(Instant.MIN)

    val name: String get() = provider.name

    /** Сколько мест к провайдеру свободно прямо сейчас (для метрики занятости). */
    val availablePermits: Int get() = bulkhead.availablePermits

    val breakerState: CircuitBreaker.State get() = breaker.state

    /** Провайдер отложен (кончилась квота): его пропускаем без попыток. */
    fun isPaused(): Boolean = clock.instant().isBefore(pausedUntil.get())

    fun pauseFor(duration: Duration) {
        pausedUntil.set(clock.instant().plus(duration))
    }

    /**
     * Один защищённый вызов провайдера.
     *
     * @throws LlmException любая ошибка приведена к типам слоя LLM.
     */
    suspend fun call(
        call: ProviderCall,
        timeout: Duration,
    ): ProviderReply {
        // withTimeoutOrNull, а не withTimeout: исключение таймаута нельзя отличить от дедлайна внешней корутины (потолок обработки
        // вопроса), и чужой дедлайн превращался бы в ложный сбой провайдера. Здесь null означает только наш собственный таймаут.
        val reply = try {
            withTimeoutOrNull(timeout.toKotlinDuration()) {
                bulkhead.withPermit {
                    breaker.executeSuspendFunction {
                        val reply = provider.chat(call)
                        // Проверка внутри ограничителя: пустой или зацикленный ответ считается сбоем провайдера.
                        reply.copy(text = validator.validate(reply.text))
                    }
                }
            }
        } catch (e: CallNotPermittedException) {
            throw LlmProviderUnavailableException("Провайдер '$name' отложен ограничителем: ${e.message}", e)
        }
        return reply ?: throw LlmTimeoutException("Провайдер '$name' не ответил за $timeout")
    }

    companion object {
        /** Ограничитель частоты ошибок для провайдера по настройкам. */
        fun newBreaker(
            providerName: String,
            settings: LlmProperties.BreakerProperties,
        ): CircuitBreaker {
            val config = CircuitBreakerConfig
                .custom()
                // Скользящее окно по времени: старые сбои «забываются» сами.
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(settings.windowSeconds)
                .minimumNumberOfCalls(settings.minimumCalls)
                .failureRateThreshold(settings.failureRatePercent.toFloat())
                .waitDurationInOpenState(settings.openStateWait)
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenProbeCalls)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Ошибка запроса (неверная модель, фильтр) не повод считать провайдера больным: не учитывается вовсе.
                .ignoreException { it is LlmException && !it.countsAsProviderFailure }
                .build()
            return CircuitBreaker.of(providerName, config)
        }
    }
}
