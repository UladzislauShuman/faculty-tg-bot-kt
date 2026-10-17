package shuman.bsu.famcs.rag.admin.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.admin.maintenance.RetentionJob
import shuman.bsu.famcs.rag.admin.maintenance.repository.RetentionRepository
import shuman.bsu.famcs.rag.admin.maintenance.repository.jdbc.RetentionRepositoryJdbcImpl
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.dialog.repository.ConversationRepository
import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.assistant.limit.service.MinuteWindow
import shuman.bsu.famcs.rag.platform.configuration.properties.RetentionProperties
import shuman.bsu.famcs.rag.platform.database.Database
import java.time.Clock

/** Сборка плановой очистки данных. */
@Configuration(proxyBeanMethods = false)
class MaintenanceConfiguration {
    /** Хранилище для очистки данных по срокам хранения. */
    @Bean
    fun retentionRepository(database: Database): RetentionRepository = RetentionRepositoryJdbcImpl(database)

    /** Ночная очистка: сообщения, журнал, счётчики и номера обновлений старше срока хранения. */
    @Bean
    fun retentionJob(
        conversations: ConversationRepository,
        processed: ProcessedUpdateRepository,
        store: RetentionRepository,
        minuteWindow: MinuteWindow,
        retention: RetentionProperties,
        limits: LimitProperties,
        clock: Clock,
    ) = RetentionJob(conversations, processed, store, minuteWindow, retention, limits.updates, clock)
}
