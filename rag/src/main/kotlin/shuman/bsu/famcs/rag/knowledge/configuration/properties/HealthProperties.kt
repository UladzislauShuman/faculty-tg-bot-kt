package shuman.bsu.famcs.rag.knowledge.configuration.properties

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки проверки здоровья (`bot.health`). */
@Validated
@ConfigurationProperties("bot.health")
data class HealthProperties(
    /** Сколько фрагментов должно быть в базе знаний, чтобы считать её готовой. Пустая база — признак, что индексация не прошла. */
    @field:Min(1) val minKnowledgeChunks: Long,
)
