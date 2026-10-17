package shuman.bsu.famcs.rag.maintenance

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.dialog.ConversationStore
import shuman.bsu.famcs.rag.limits.LimitsProperties
import shuman.bsu.famcs.rag.limits.MinuteWindow
import shuman.bsu.famcs.rag.limits.ProcessedUpdates
import java.time.Clock

/** Сборка плановой очистки данных. */
@Configuration(proxyBeanMethods = false)
class MaintenanceConfiguration {
    @Bean
    fun retentionStore(database: Database): RetentionStore = JdbcRetentionStore(database)

    @Bean
    fun retentionJob(
        conversations: ConversationStore,
        processed: ProcessedUpdates,
        store: RetentionStore,
        minuteWindow: MinuteWindow,
        retention: RetentionProperties,
        limits: LimitsProperties,
        clock: Clock,
    ) = RetentionJob(conversations, processed, store, minuteWindow, retention, limits.updates, clock)
}
