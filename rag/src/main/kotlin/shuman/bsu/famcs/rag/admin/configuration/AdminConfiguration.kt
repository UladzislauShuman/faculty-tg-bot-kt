package shuman.bsu.famcs.rag.admin.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.admin.configuration.properties.AdminProperties
import shuman.bsu.famcs.rag.admin.repository.AdminAuditRepository
import shuman.bsu.famcs.rag.admin.repository.StatisticsRepository
import shuman.bsu.famcs.rag.admin.repository.jdbc.AdminAuditRepositoryJdbcImpl
import shuman.bsu.famcs.rag.admin.repository.jdbc.StatisticsRepositoryJdbcImpl
import shuman.bsu.famcs.rag.admin.service.implementation.AdminServiceImpl
import shuman.bsu.famcs.rag.api.AdminService
import shuman.bsu.famcs.rag.assistant.pipeline.service.MaintenanceSwitch
import shuman.bsu.famcs.rag.knowledge.indexing.job.IndexingJob
import shuman.bsu.famcs.rag.platform.database.Database
import java.time.Clock

/** Сборка административных команд: хранилища отчётов и журнала, сам сервис. */
@Configuration(proxyBeanMethods = false)
class AdminConfiguration {
    /** Выборки для отчётов `/admin_stats`, `/admin_feedback`, `/admin_gaps`. */
    @Bean
    fun statisticsRepository(database: Database): StatisticsRepository = StatisticsRepositoryJdbcImpl(database)

    /** Журнал вызовов административных команд. */
    @Bean
    fun adminAuditRepository(database: Database): AdminAuditRepository = AdminAuditRepositoryJdbcImpl(database)

    /** Сервис команд администратора, который вызывает адаптер Telegram. */
    @Bean
    fun adminService(
        statistics: StatisticsRepository,
        audit: AdminAuditRepository,
        indexing: IndexingJob,
        maintenance: MaintenanceSwitch,
        properties: AdminProperties,
        clock: Clock,
    ): AdminService = AdminServiceImpl(statistics, audit, indexing, maintenance, properties, clock)
}
