package shuman.bsu.famcs.rag.platform.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.platform.configuration.properties.BotProperties
import java.time.Clock
import java.time.ZoneId

/**
 * Единственный источник текущего времени для приложения.
 *
 * Зачем: код не вызывает `Instant.now()` напрямую. В тестах подставляются фиксированные часы, и сценарий
 * «какая пара в среду в 11:00» воспроизводится в любой день.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    /** Часы приложения в часовом поясе бота (`bot.time-zone`). */
    @Bean
    fun clock(properties: BotProperties): Clock = Clock.system(ZoneId.of(properties.timeZone))
}
