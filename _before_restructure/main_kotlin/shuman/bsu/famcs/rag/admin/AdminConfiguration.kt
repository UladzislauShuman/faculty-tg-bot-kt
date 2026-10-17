package shuman.bsu.famcs.rag.admin

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.core.MaintenanceSwitch
import shuman.bsu.famcs.rag.knowledge.indexing.IndexingJob
import java.time.Clock

/** Сборка административных команд. */
@Configuration(proxyBeanMethods = false)
class AdminConfiguration {
    @Bean
    fun adminService(
        database: Database,
        indexing: IndexingJob,
        maintenance: MaintenanceSwitch,
        properties: AdminProperties,
        clock: Clock,
    ) = AdminService(database, indexing, maintenance, properties, clock)
}
