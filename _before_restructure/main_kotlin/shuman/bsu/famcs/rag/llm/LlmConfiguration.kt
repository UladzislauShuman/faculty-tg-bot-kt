package shuman.bsu.famcs.rag.llm

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.common.TokenEstimator
import shuman.bsu.famcs.rag.common.TokenProperties
import shuman.bsu.famcs.rag.core.Alerts
import java.time.Clock

/**
 * Сборка слоя LLM из конфигурации: провайдеры, защита вокруг них, клиент по ролям.
 *
 * Ошибки конфигурации (цепочка ссылается на неизвестного провайдера, не хватает роли) обнаруживаются при старте,
 * а не при первом вопросе пользователя.
 */
@Configuration(proxyBeanMethods = false)
class LlmConfiguration {
    @Bean
    fun tokenEstimator(properties: TokenProperties) = TokenEstimator(charsPerToken = properties.charsPerToken)

    @Bean
    fun replyValidator(properties: LlmProperties) = ReplyValidator(settings = properties.replyValidation)

    @Bean
    fun llmClient(
        properties: LlmProperties,
        validator: ReplyValidator,
        tokenEstimator: TokenEstimator,
        usageListeners: List<LlmUsageListener>,
        meters: MeterRegistry,
        clock: Clock,
        alerts: Alerts,
    ): LlmClient {
        // Создаются только провайдеры, на которых стоят роли: описанный, но не подключённый (например, Yandex без ключа
        // при запуске на локальном Ollama) запуску не мешает.
        val used = properties.roles.values
            .flatMap { role -> role.chain.map { it.provider } }
            .toSet()
        val managed = properties.providers.filterKeys { it in used }.mapValues { (name, settings) ->
            ManagedProvider(
                provider = ChatModelProvider(name = name, chatModel = ChatModelFactory.create(name, settings)),
                maxConcurrentCalls = settings.maxConcurrentCalls,
                breaker = ManagedProvider.newBreaker(name, properties.resilience.breaker),
                validator = validator,
                clock = clock,
            )
        }

        managed.values.forEach { registerGauges(it, meters) }

        val roles = LlmRole.entries.associateWith { role ->
            val roleSettings = requireNotNull(properties.roles[role]) { "Не задана роль $role в bot.llm.roles" }
            val chain = roleSettings.chain.map { link ->
                val provider = requireNotNull(managed[link.provider]) {
                    "Роль $role ссылается на неизвестного провайдера '${link.provider}'. Описаны: ${properties.providers.keys}"
                }
                ResilientLlmClient.ChainStep(provider = provider, model = link.model)
            }
            ResilientLlmClient.RoleRuntime(settings = roleSettings, chain = chain)
        }

        return ResilientLlmClient(
            roles = roles,
            retry = properties.resilience.retry,
            quotaPause = properties.resilience.quotaPause,
            tokenEstimator = tokenEstimator,
            usageListeners = usageListeners,
            meters = meters,
            alerts = alerts,
        )
    }

    /** Датчики провайдера: сколько мест занято и в каком состоянии размыкатель (0 — работает, 1 — проба, 2 — отключён). */
    private fun registerGauges(
        provider: ManagedProvider,
        meters: MeterRegistry,
    ) {
        Gauge
            .builder("llm.bulkhead.available", provider) { it.availablePermits.toDouble() }
            .tag("provider", provider.name)
            .description("Свободных мест вызова модели у провайдера")
            .register(meters)
        Gauge
            .builder("llm.breaker.state", provider) { breakerValue(it) }
            .tag("provider", provider.name)
            .description("Размыкатель провайдера: 0 — работает, 1 — проба, 2 — отключён")
            .register(meters)
    }

    private fun breakerValue(provider: ManagedProvider): Double = when (provider.breakerState) {
        CircuitBreaker.State.OPEN, CircuitBreaker.State.FORCED_OPEN -> BREAKER_OPEN
        CircuitBreaker.State.HALF_OPEN -> BREAKER_HALF_OPEN
        else -> BREAKER_CLOSED
    }

    private companion object {
        const val BREAKER_CLOSED = 0.0
        const val BREAKER_HALF_OPEN = 1.0
        const val BREAKER_OPEN = 2.0
    }
}
