package shuman.bsu.famcs.rag.platform.configuration

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.platform.configuration.properties.AlertsProperties
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import shuman.bsu.famcs.rag.platform.notification.Alerts
import shuman.bsu.famcs.rag.platform.notification.LoggingAdminNotifier
import java.time.Clock

/** Сборка оповещений администратора: ограничитель частоты и запасной канал через журнал. */
@Configuration(proxyBeanMethods = false)
class AlertsConfiguration {
    /** Оповещения с ограничением частоты: одно и то же событие не рассылается чаще, чем раз в `minInterval`. */
    @Bean
    fun alerts(
        notifier: AdminNotifier,
        clock: Clock,
        properties: AlertsProperties,
    ) = Alerts(notifier = notifier, clock = clock, minInterval = properties.minInterval)

    /** Оповещение через журнал: используется, пока Telegram выключен. С включённым Telegram бин даёт адаптер. */
    @Bean
    @ConditionalOnProperty(prefix = "bot.telegram", name = ["enabled"], havingValue = "false")
    fun loggingAdminNotifier(): AdminNotifier = LoggingAdminNotifier
}
