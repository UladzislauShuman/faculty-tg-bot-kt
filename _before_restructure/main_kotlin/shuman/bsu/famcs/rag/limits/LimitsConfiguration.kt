package shuman.bsu.famcs.rag.limits

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.UserHasher
import shuman.bsu.famcs.rag.core.AdminNotifier
import shuman.bsu.famcs.rag.core.LoggingAdminNotifier
import java.time.Clock
import java.time.Duration

/**
 * Сборка модуля лимитов: счётчики, защита бюджета, очередь.
 * Бюджетный предохранитель одновременно слушает расход токенов каждого вызова модели ([shuman.bsu.famcs.rag.llm.LlmUsageListener]).
 */
@Configuration(proxyBeanMethods = false)
class LimitsConfiguration {
    @Bean
    fun usageStore(
        database: Database,
        hasher: UserHasher,
    ): UsageStore = JdbcUsageStore(database, hasher)

    @Bean
    fun processedUpdates(database: Database): ProcessedUpdates = JdbcProcessedUpdates(database)

    @Bean
    fun budgetGuard(
        usage: UsageStore,
        properties: LimitsProperties,
        clock: Clock,
        notifier: AdminNotifier,
    ) = BudgetGuard(usage = usage, limits = properties.system, clock = clock, notifier = notifier)

    @Bean
    fun minuteWindow(properties: LimitsProperties) = MinuteWindow(perMinute = properties.user.perMinute, window = Duration.ofMinutes(1))

    @Bean
    fun limitsGate(
        window: MinuteWindow,
        budget: BudgetGuard,
        usage: UsageStore,
        properties: LimitsProperties,
        clock: Clock,
    ) = LimitsGate(inFlight = InFlightGuard(), minute = window, budget = budget, usage = usage, limits = properties.user, clock = clock)

    @Bean
    fun admissionGate(
        properties: LimitsProperties,
        meters: MeterRegistry,
    ): AdmissionGate {
        val gate = AdmissionGate(
            maxConcurrent = properties.queue.maxConcurrent,
            maxWaiting = properties.queue.maxWaiting,
            maxWait = properties.queue.maxWait,
        )
        // Датчики очереди: по ним видно, что бот упёрся в потолок (ожидающих много, мест нет).
        Gauge
            .builder(
                "bot.queue.waiting",
                gate,
            ) { it.waitingCount.toDouble() }
            .description("Вопросов ждёт места в очереди")
            .register(meters)
        Gauge.builder("bot.queue.in_use", gate) { it.inUse.toDouble() }.description("Вопросов обрабатывается сейчас").register(meters)
        return gate
    }

    /** Оповещение через журнал: используется, пока Telegram выключен. С включённым Telegram бин даёт адаптер. */
    @Bean
    @ConditionalOnProperty(prefix = "bot.telegram", name = ["enabled"], havingValue = "false")
    fun loggingAdminNotifier(): AdminNotifier = LoggingAdminNotifier
}
