package shuman.bsu.famcs.rag.web

import jakarta.validation.constraints.Min
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader

/** Настройки проверки здоровья (`bot.health`). */
@Validated
@ConfigurationProperties("bot.health")
data class HealthProperties(
    /** Сколько фрагментов должно быть в базе знаний, чтобы считать её готовой. Пустая база — признак, что индексация не прошла. */
    @field:Min(1) val minKnowledgeChunks: Long,
)

/**
 * Проба базы знаний: Qdrant отвечает и в нём достаточно фрагментов.
 * Доступность Postgres проверяет стандартный индикатор Spring Boot, поэтому здесь только Qdrant.
 */
@Component("knowledge")
class KnowledgeHealthIndicator(
    private val reader: KnowledgeBaseReader,
    private val properties: HealthProperties,
) : HealthIndicator {
    override fun health(): Health = try {
        val chunks = runBlocking { reader.chunkCount() }
        val builder = if (chunks >= properties.minKnowledgeChunks) Health.up() else Health.down()
        builder.withDetail("chunks", chunks).withDetail("minimum", properties.minKnowledgeChunks).build()
    } catch (e: Exception) {
        Health.down().withDetail("error", e.javaClass.simpleName).build()
    }
}
