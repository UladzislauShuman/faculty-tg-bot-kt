package shuman.bsu.famcs.rag.ui.telegram.configuration

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.telegramBot
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.api.AdminService
import shuman.bsu.famcs.rag.api.ConversationService
import shuman.bsu.famcs.rag.api.FeedbackService
import shuman.bsu.famcs.rag.api.RagService
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.platform.configuration.properties.RetentionProperties
import shuman.bsu.famcs.rag.platform.configuration.properties.RuntimeProperties
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import shuman.bsu.famcs.rag.platform.runtime.ApplicationScope
import shuman.bsu.famcs.rag.platform.text.Templates
import shuman.bsu.famcs.rag.ui.telegram.configuration.properties.TelegramProperties
import shuman.bsu.famcs.rag.ui.telegram.dispatcher.UpdateDispatcher
import shuman.bsu.famcs.rag.ui.telegram.gateway.TelegramGateway
import shuman.bsu.famcs.rag.ui.telegram.gateway.implementation.KtgbotapiGateway
import shuman.bsu.famcs.rag.ui.telegram.handler.TelegramBotHandler
import shuman.bsu.famcs.rag.ui.telegram.handler.TelegramHandlerFactory
import shuman.bsu.famcs.rag.ui.telegram.logging.KsLogBridge
import shuman.bsu.famcs.rag.ui.telegram.notification.TelegramAdminNotifier
import shuman.bsu.famcs.rag.ui.telegram.poller.TelegramPoller
import java.time.Clock
import kotlin.time.toKotlinDuration

/**
 * Сборка адаптера Telegram. Включается флагом `bot.telegram.enabled`; без него ядро работает без Telegram (HTTP-вход, тесты).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bot.telegram", name = ["enabled"], havingValue = "true")
class TelegramConfiguration {
    /** Клиент Telegram. Журнал библиотеки подключается к нашему до первого вызова: иначе токен мог бы попасть в лог. */
    @Bean
    fun telegramBot(properties: TelegramProperties): TelegramBot {
        // До создания бота: библиотека пишет мимо SLF4J, её журнал нужно взять под контроль раньше первого вызова.
        KsLogBridge.install()
        return telegramBot(requireNotNull(properties.token))
    }

    /** Шлюз отправки сообщений через ktgbotapi. */
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

    /**
     * Логика бота: команды, согласие, оценки. Срок хранения в тексте согласия берётся из настроек очистки,
     * чтобы обещание пользователю не разошлось с делом.
     */
    @Bean
    fun telegramBotHandler(
        core: RagService,
        users: UserService,
        conversations: ConversationService,
        feedback: FeedbackService,
        admin: AdminService,
        gateway: TelegramGateway,
        consent: ConsentProperties,
        retention: RetentionProperties,
        properties: TelegramProperties,
        clock: Clock,
    ): TelegramBotHandler {
        // Срок хранения в тексте согласия берётся из настроек очистки, чтобы обещание пользователю не разошлось с делом.
        val renderedConsent = consent.copy(text = Templates.fill(consent.text, "retention_days" to retention.messagesDays.toString()))
        return TelegramHandlerFactory.create(core, users, conversations, feedback, admin, gateway, renderedConsent, properties, clock)
    }

    /** Диспетчер обновлений: ограничивает число одновременных обработчиков и дожидается их при остановке. */
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

    /** Приём обновлений длинным опросом; запускается и останавливается вместе с приложением. */
    @Bean
    fun telegramPoller(
        bot: TelegramBot,
        handler: TelegramBotHandler,
        scope: ApplicationScope,
        dispatcher: UpdateDispatcher,
        properties: TelegramProperties,
    ) = TelegramPoller(
        bot,
        handler,
        scope,
        dispatcher,
        pollTimeoutSeconds = properties.pollTimeout.seconds.toInt(),
        errorPause = properties.errorPause,
    )
}
