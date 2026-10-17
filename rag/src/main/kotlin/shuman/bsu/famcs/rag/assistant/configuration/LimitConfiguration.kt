package shuman.bsu.famcs.rag.assistant.configuration

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.assistant.limit.repository.UsageRepository
import shuman.bsu.famcs.rag.assistant.limit.repository.jdbc.ProcessedUpdateRepositoryJdbcImpl
import shuman.bsu.famcs.rag.assistant.limit.repository.jdbc.UsageRepositoryJdbcImpl
import shuman.bsu.famcs.rag.assistant.limit.service.BudgetGuard
import shuman.bsu.famcs.rag.assistant.limit.service.InFlightGuard
import shuman.bsu.famcs.rag.assistant.limit.service.MinuteWindow
import shuman.bsu.famcs.rag.assistant.limit.service.implementation.RequestLimiterImpl
import shuman.bsu.famcs.rag.assistant.limit.service.implementation.WorkQueueImpl
import shuman.bsu.famcs.rag.llm.service.LlmUsageListener
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import java.time.Clock
import java.time.Duration

/**
 * Сборка модуля лимитов: счётчики, защита бюджета, очередь.
 * Бюджетный предохранитель одновременно слушает расход токенов каждого вызова модели ([LlmUsageListener]).
 */
@Configuration(proxyBeanMethods = false)
class LimitConfiguration {
    /** Счётчики расхода: вопросы пользователей и токены системы по дням. */
    @Bean
    fun usageRepository(
        database: Database,
        hasher: UserHasher,
    ): UsageRepository = UsageRepositoryJdbcImpl(database, hasher)

    /** Учёт номеров обновлений Telegram: повторная доставка не обрабатывается дважды. */
    @Bean
    fun processedUpdateRepository(database: Database): ProcessedUpdateRepository = ProcessedUpdateRepositoryJdbcImpl(database)

    /** Предохранитель суточного бюджета токенов: режимы NORMAL, ECONOMY, EXHAUSTED и оповещения администратору. */
    @Bean
    fun budgetGuard(
        usage: UsageRepository,
        properties: LimitProperties,
        clock: Clock,
        notifier: AdminNotifier,
    ) = BudgetGuard(usage = usage, limits = properties.system, clock = clock, notifier = notifier)

    /** Скользящее окно «вопросов в минуту» на пользователя. */
    @Bean
    fun minuteWindow(properties: LimitProperties) = MinuteWindow(perMinute = properties.user.perMinute, window = Duration.ofMinutes(1))

    /** Лимиты на пользователя: один вопрос за раз, минутный и суточный лимит, бюджет системы. */
    @Bean
    fun requestLimiter(
        window: MinuteWindow,
        budget: BudgetGuard,
        usage: UsageRepository,
        properties: LimitProperties,
        clock: Clock,
    ) = RequestLimiterImpl(
        inFlight = InFlightGuard(),
        minute = window,
        budget = budget,
        usage = usage,
        limits = properties.user,
        clock = clock,
    )

    /**
     * Очередь вопросов: ограничено число одновременных обработок, число ожидающих и время ожидания.
     * Датчики очереди регистрируются в метриках.
     */
    @Bean
    fun workQueue(
        properties: LimitProperties,
        meters: MeterRegistry,
    ): WorkQueueImpl {
        val gate = WorkQueueImpl(
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
}
