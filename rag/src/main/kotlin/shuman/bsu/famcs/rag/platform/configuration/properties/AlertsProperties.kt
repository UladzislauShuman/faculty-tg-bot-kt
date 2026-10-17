package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/** Настройки оповещений (`bot.alerts`). */
@Validated
@ConfigurationProperties("bot.alerts")
data class AlertsProperties(
    /** Как часто повторяется оповещение одного вида. Сбой, который длится часами, не должен засыпать администратора сообщениями. */
    @field:NotNull val minInterval: Duration,
)
