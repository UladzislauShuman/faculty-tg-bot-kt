package shuman.bsu.famcs.rag.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.telegramBot
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.admin.AdminCommands
import shuman.bsu.famcs.rag.app.ApplicationScope
import shuman.bsu.famcs.rag.app.RuntimeProperties
import shuman.bsu.famcs.rag.common.Templates
import shuman.bsu.famcs.rag.core.AdminNotifier
import shuman.bsu.famcs.rag.core.BotCore
import shuman.bsu.famcs.rag.dialog.ConsentProperties
import shuman.bsu.famcs.rag.dialog.ContextManager
import shuman.bsu.famcs.rag.dialog.UserDirectory
import shuman.bsu.famcs.rag.feedback.FeedbackService
import shuman.bsu.famcs.rag.maintenance.RetentionProperties
import java.time.Clock
import kotlin.time.toKotlinDuration

/**
 * Сборка адаптера Telegram. Включается флагом `bot.telegram.enabled`; без него ядро работает без Telegram (HTTP-вход, тесты).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bot.telegram", name = ["enabled"], havingValue = "true")
class TelegramConfiguration {
    @Bean
    fun telegramBot(properties: TelegramProperties): TelegramBot {
        // До создания бота: библиотека пишет мимо SLF4J, её журнал нужно взять под контроль раньше первого вызова.
        KsLogBridge.install()
        return telegramBot(requireNotNull(properties.token))
    }

    @Bean
    fun telegramGateway(
        bot: TelegramBot,
        properties: TelegramProperties,
    ): TelegramGateway = KtgbotapiGateway(bot, retries = properties.sendRetries)

    /** Администраторы получают оповещения личными сообщениями. */
    @Bean
    fun telegramAdminNotifier(
        gateway: TelegramGateway,
        properties: TelegramProperties,
    ): AdminNotifier = TelegramAdminNotifier(gateway, properties.adminIds)

    @Bean
    fun telegramBotHandler(
        core: BotCore,
        users: UserDirectory,
        contexts: ContextManager,
        feedback: FeedbackService,
        admin: AdminCommands,
        gateway: TelegramGateway,
        consent: ConsentProperties,
        retention: RetentionProperties,
        properties: TelegramProperties,
        clock: Clock,
    ): TelegramBotHandler {
        // Срок хранения в тексте согласия берётся из настроек очистки, чтобы обещание пользователю не разошлось с делом.
        val renderedConsent = consent.copy(text = Templates.fill(consent.text, "retention_days" to retention.messagesDays.toString()))
        return TelegramBotHandler(core, users, contexts, feedback, admin, gateway, renderedConsent, properties, clock)
    }

    @Bean
    fun updateDispatcher(
        scope: ApplicationScope,
        properties: TelegramProperties,
        runtime: RuntimeProperties,
        meters: MeterRegistry,
    ) = UpdateDispatcher(
        scope = scope,
        maxConcurrent = properties.maxConcurrentUpdates,
        gracePeriod = runtime.shutdownGracePeriod.toKotlinDuration(),
        onDropped = { meters.counter("bot.updates.dropped").increment() },
    )

    @Bean
    fun telegramPoller(
        bot: TelegramBot,
        handler: TelegramBotHandler,
        scope: ApplicationScope,
        dispatcher: UpdateDispatcher,
        properties: TelegramProperties,
    ) = TelegramPoller(bot, handler, scope, dispatcher, pollTimeoutSeconds = properties.pollTimeout.seconds.toInt())
}
