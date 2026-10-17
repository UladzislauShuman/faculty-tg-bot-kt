package shuman.bsu.famcs.rag.admin.configuration.properties

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/** Настройки административных команд (`bot.admin`). */
@Validated
@ConfigurationProperties("bot.admin")
data class AdminProperties(
    /** За какой период считается статистика `/admin_stats`. */
    val statsWindow: Duration,
    /** Период, за который собираются вопросы без ответа в `/admin_gaps`. */
    val gapsWindow: Duration,
    /** Сколько строк показывать в списках `/admin_feedback` и `/admin_gaps`. */
    @field:Positive val listLimit: Int,
    /** Сколько символов вопроса и ответа показывать в разборе низких оценок. */
    @field:Positive val excerptChars: Int,
)
