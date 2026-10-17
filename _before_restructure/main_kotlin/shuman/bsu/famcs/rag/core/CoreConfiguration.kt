package shuman.bsu.famcs.rag.core

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Сборка общих частей ядра. */
@Configuration(proxyBeanMethods = false)
class CoreConfiguration {
    @Bean
    fun alerts(
        notifier: AdminNotifier,
        clock: Clock,
        properties: AlertsProperties,
    ) = Alerts(notifier = notifier, clock = clock, minInterval = properties.minInterval)
}
