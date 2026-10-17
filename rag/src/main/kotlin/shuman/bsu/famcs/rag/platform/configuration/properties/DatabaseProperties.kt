package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки доступа к базе (`bot.database`). Параметры пула соединений лежат в `spring.datasource.hikari`. */
@Validated
@ConfigurationProperties("bot.database")
data class DatabaseProperties(
    /** Сколько запросов к базе выполняется одновременно. Не больше размера пула соединений Hikari. */
    @field:Positive val maxParallelQueries: Int,
)
