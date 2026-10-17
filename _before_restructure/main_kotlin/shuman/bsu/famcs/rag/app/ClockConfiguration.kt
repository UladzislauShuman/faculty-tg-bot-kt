package shuman.bsu.famcs.rag.app

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import java.time.Clock
import java.time.ZoneId

/** Общие настройки бота (корень `bot`). */
@Validated
@ConfigurationProperties("bot")
data class BotProperties(
    /** Идентификатор часового пояса IANA. Для факультета в Минске — Europe/Minsk. Расписание и суточные счётчики считаются по нему. */
    @field:NotBlank val timeZone: String,
    /** Режим обслуживания при старте. Администратор переключает его командой без перезапуска. */
    val maintenanceEnabled: Boolean,
)

/**
 * Единственный источник текущего времени для приложения.
 *
 * Зачем: код не вызывает `Instant.now()` напрямую. В тестах подставляются фиксированные часы, и сценарий
 * «какая пара в среду в 11:00» воспроизводится в любой день.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    @Bean
    fun clock(properties: BotProperties): Clock = Clock.system(ZoneId.of(properties.timeZone))
}
