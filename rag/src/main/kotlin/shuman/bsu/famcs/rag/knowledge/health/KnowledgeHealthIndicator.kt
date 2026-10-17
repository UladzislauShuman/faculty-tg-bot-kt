package shuman.bsu.famcs.rag.knowledge.health

import kotlinx.coroutines.runBlocking
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import shuman.bsu.famcs.rag.knowledge.configuration.properties.HealthProperties
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader

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
