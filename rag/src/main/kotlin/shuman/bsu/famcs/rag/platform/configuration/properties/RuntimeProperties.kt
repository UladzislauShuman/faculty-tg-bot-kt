package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Настройки среды выполнения (`bot.runtime`). */
@ConfigurationProperties("bot.runtime")
data class RuntimeProperties(
    /** Сколько потоков отдано вычислениям моделей на процессоре (эмбеддинги и реранкер вместе). */
    @field:Positive val cpuThreads: Int,
    /** Сколько ждать завершения фоновых задач при остановке приложения. */
    val shutdownGracePeriod: Duration,
)
