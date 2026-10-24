package shuman.bsu.famcs.rag.assistant.configuration

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.api.RagService
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.assistant.configuration.properties.BotMessagesProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.ReplyProperties
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.QueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.service.ContextManager
import shuman.bsu.famcs.rag.assistant.feedback.repository.AnswerLogRepository
import shuman.bsu.famcs.rag.assistant.intent.classifier.IntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.handler.IntentHandler
import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.assistant.limit.repository.UsageRepository
import shuman.bsu.famcs.rag.assistant.limit.service.RequestLimiter
import shuman.bsu.famcs.rag.assistant.limit.service.WorkQueue
import shuman.bsu.famcs.rag.assistant.pipeline.service.AnswerPipeline
import shuman.bsu.famcs.rag.assistant.pipeline.service.AnswerRecorder
import shuman.bsu.famcs.rag.assistant.pipeline.service.CostFooter
import shuman.bsu.famcs.rag.assistant.pipeline.service.MaintenanceSwitch
import shuman.bsu.famcs.rag.assistant.pipeline.service.Prechecks
import shuman.bsu.famcs.rag.assistant.pipeline.service.ReplyAssembler
import shuman.bsu.famcs.rag.assistant.pipeline.service.implementation.RagServiceImpl
import shuman.bsu.famcs.rag.platform.configuration.properties.BotProperties
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.platform.notification.Alerts
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import java.time.Clock

/**
 * Сборка конвейера ответа: предпроверки, оркестратор, запись результата, сборка текста и вход для адаптеров ([RagService]).
 */
@Configuration(proxyBeanMethods = false)
class PipelineConfiguration {
    /** Конвейер ответа: контекст, переформулирование, классификатор, обработчик ветки. */
    @Bean
    fun answerPipeline(
        contexts: ContextManager,
        rewriter: QueryRewriter,
        classifier: IntentClassifier,
        handlers: List<IntentHandler>,
        meters: MeterRegistry,
    ) = AnswerPipeline(contexts, rewriter, classifier, handlers, meters)

    /** Переключатель режима обслуживания; начальное состояние берётся из настроек. */
    @Bean
    fun maintenanceSwitch(properties: BotProperties) = MaintenanceSwitch(initial = properties.maintenanceEnabled)

    /** Предпроверки сообщения до дорогой работы: возраст, дубль, согласие, обслуживание, тип и длина. */
    @Bean
    fun prechecks(
        processed: ProcessedUpdateRepository,
        users: UserService,
        maintenance: MaintenanceSwitch,
        consent: ConsentProperties,
        limits: LimitProperties,
        messages: BotMessagesProperties,
        clock: Clock,
    ) = Prechecks(processed, users, maintenance, consent, limits.updates, limits.input, messages, clock)

    /** Запись результата: сообщения диалога, журнал ответов, счётчики. */
    @Bean
    fun answerRecorder(
        contexts: ContextManager,
        answers: AnswerLogRepository,
        usage: UsageRepository,
        hasher: UserHasher,
        clock: Clock,
    ) = AnswerRecorder(contexts, answers, usage, hasher, clock)

    /** Сборка итогового текста: источники, дата проверки, оговорка про даты. */
    @Bean
    fun replyAssembler(
        messages: BotMessagesProperties,
        properties: ReplyProperties,
    ) = ReplyAssembler(messages, properties)

    /** Вход в систему для адаптеров: предпроверки, лимиты, очередь, конвейер, запись, сборка ответа. */
    @Bean
    fun ragService(
        prechecks: Prechecks,
        limits: RequestLimiter,
        queueGate: WorkQueue,
        pipeline: AnswerPipeline,
        recorder: AnswerRecorder,
        assembler: ReplyAssembler,
        messages: BotMessagesProperties,
        properties: LimitProperties,
        alerts: Alerts,
        meters: MeterRegistry,
        reply: ReplyProperties,
    ): RagService = RagServiceImpl(
        prechecks,
        limits,
        queueGate,
        pipeline,
        recorder,
        assembler,
        messages,
        properties.queue,
        alerts,
        meters,
        CostFooter(reply.cost),
    )
}
