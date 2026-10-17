package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Общие настройки бота (корень `bot`). */
@Validated
@ConfigurationProperties("bot")
data class BotProperties(
    /** Идентификатор часового пояса IANA. Для факультета в Минске — Europe/Minsk. Расписание и суточные счётчики считаются по нему. */
    @field:NotBlank val timeZone: String,
    /** Режим обслуживания при старте. Администратор переключает его командой без перезапуска. */
    val maintenanceEnabled: Boolean,
)
