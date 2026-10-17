package shuman.bsu.famcs.rag.assistant.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.api.FeedbackService
import shuman.bsu.famcs.rag.assistant.feedback.repository.AnswerLogRepository
import shuman.bsu.famcs.rag.assistant.feedback.repository.FeedbackRepository
import shuman.bsu.famcs.rag.assistant.feedback.repository.jdbc.AnswerLogRepositoryJdbcImpl
import shuman.bsu.famcs.rag.assistant.feedback.repository.jdbc.FeedbackRepositoryJdbcImpl
import shuman.bsu.famcs.rag.assistant.feedback.service.implementation.FeedbackServiceImpl
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import java.time.Clock

/** Сборка модуля обратной связи: журнал ответов и оценки пользователей. */
@Configuration(proxyBeanMethods = false)
class FeedbackConfiguration {
    /** Журнал ответов: по записи журнала пользователь оценивает ответ. */
    @Bean
    fun answerLogRepository(database: Database): AnswerLogRepository = AnswerLogRepositoryJdbcImpl(database)

    /** Хранилище оценок в PostgreSQL. */
    @Bean
    fun feedbackRepository(
        database: Database,
        hasher: UserHasher,
    ): FeedbackRepository = FeedbackRepositoryJdbcImpl(database, hasher)

    /** Сервис оценок, который вызывают адаптеры. */
    @Bean
    fun feedbackService(
        repository: FeedbackRepository,
        clock: Clock,
    ): FeedbackService = FeedbackServiceImpl(repository, clock)
}
