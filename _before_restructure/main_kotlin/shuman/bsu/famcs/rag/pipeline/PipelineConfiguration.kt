package shuman.bsu.famcs.rag.pipeline

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.app.BotProperties
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.common.UserHasher
import shuman.bsu.famcs.rag.core.Alerts
import shuman.bsu.famcs.rag.core.BotCore
import shuman.bsu.famcs.rag.core.BotMessages
import shuman.bsu.famcs.rag.core.MaintenanceSwitch
import shuman.bsu.famcs.rag.dialog.ConsentProperties
import shuman.bsu.famcs.rag.dialog.ContextManager
import shuman.bsu.famcs.rag.dialog.QueryRewriter
import shuman.bsu.famcs.rag.dialog.UserDirectory
import shuman.bsu.famcs.rag.feedback.AnswerLog
import shuman.bsu.famcs.rag.feedback.FeedbackService
import shuman.bsu.famcs.rag.feedback.JdbcAnswerLog
import shuman.bsu.famcs.rag.feedback.JdbcFeedbackService
import shuman.bsu.famcs.rag.intent.AlwaysRagClassifier
import shuman.bsu.famcs.rag.intent.ChatHandler
import shuman.bsu.famcs.rag.intent.GreetingMatcher
import shuman.bsu.famcs.rag.intent.Intent
import shuman.bsu.famcs.rag.intent.IntentClassifier
import shuman.bsu.famcs.rag.intent.IntentHandler
import shuman.bsu.famcs.rag.intent.IntentProperties
import shuman.bsu.famcs.rag.intent.LlmIntentClassifier
import shuman.bsu.famcs.rag.intent.RagHandler
import shuman.bsu.famcs.rag.intent.RagProperties
import shuman.bsu.famcs.rag.intent.ScheduleLinkHandler
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.limits.LimitsProperties
import shuman.bsu.famcs.rag.limits.ProcessedUpdates
import shuman.bsu.famcs.rag.limits.RequestLimiter
import shuman.bsu.famcs.rag.limits.UsageStore
import shuman.bsu.famcs.rag.limits.WorkQueue
import shuman.bsu.famcs.rag.llm.LlmClient
import java.time.Clock

/**
 * Сборка ядра: обработчики веток, классификатор, конвейер, предпроверки и вход для адаптеров.
 * Новая ветка добавляется новым бином [IntentHandler] и значением в [Intent]; остальной код не меняется.
 */
@Configuration(proxyBeanMethods = false)
class PipelineConfiguration {
    // --- Ветки ---

    @Bean
    fun ragHandler(
        retriever: KnowledgeRetriever,
        llm: LlmClient,
        prompts: PromptLibrary,
        properties: RagProperties,
        messages: BotMessages,
        clock: Clock,
    ): IntentHandler = RagHandler(
        retriever = retriever,
        llm = llm,
        prompts = prompts,
        properties = properties,
        noInfoText = messages.noInfo,
        zone = clock.zone,
    )

    @Bean
    fun smalltalkHandler(
        llm: LlmClient,
        prompts: PromptLibrary,
    ): IntentHandler = ChatHandler(Intent.SMALLTALK, "smalltalk-system", llm, prompts)

    @Bean
    fun generalHandler(
        llm: LlmClient,
        prompts: PromptLibrary,
    ): IntentHandler = ChatHandler(Intent.GENERAL, "general-system", llm, prompts)

    @Bean
    fun scheduleHandler(messages: BotMessages): IntentHandler = ScheduleLinkHandler(messages.schedule)

    @Bean
    fun intentClassifier(
        llm: LlmClient,
        prompts: PromptLibrary,
        properties: IntentProperties,
    ): IntentClassifier = when (properties.mode) {
        IntentProperties.Mode.OFF -> AlwaysRagClassifier
        IntentProperties.Mode.LLM -> LlmIntentClassifier(llm, prompts, GreetingMatcher())
    }

    // --- Конвейер и ядро ---

    @Bean
    fun answerPipeline(
        contexts: ContextManager,
        rewriter: QueryRewriter,
        classifier: IntentClassifier,
        handlers: List<IntentHandler>,
        meters: MeterRegistry,
    ) = AnswerPipeline(contexts, rewriter, classifier, handlers, meters)

    @Bean
    fun maintenanceSwitch(properties: BotProperties) = MaintenanceSwitch(initial = properties.maintenanceEnabled)

    @Bean
    fun prechecks(
        processed: ProcessedUpdates,
        users: UserDirectory,
        maintenance: MaintenanceSwitch,
        consent: ConsentProperties,
        limits: LimitsProperties,
        messages: BotMessages,
        clock: Clock,
    ) = Prechecks(processed, users, maintenance, consent, limits.updates, limits.input, messages, clock)

    @Bean
    fun answerLog(database: Database): AnswerLog = JdbcAnswerLog(database)

    @Bean
    fun answerRecorder(
        contexts: ContextManager,
        answers: AnswerLog,
        usage: UsageStore,
        hasher: UserHasher,
        clock: Clock,
    ) = AnswerRecorder(contexts, answers, usage, hasher, clock)

    @Bean
    fun replyAssembler(
        messages: BotMessages,
        properties: ReplyProperties,
    ) = ReplyAssembler(messages, properties)

    @Bean
    fun botCore(
        prechecks: Prechecks,
        limits: RequestLimiter,
        queueGate: WorkQueue,
        pipeline: AnswerPipeline,
        recorder: AnswerRecorder,
        assembler: ReplyAssembler,
        messages: BotMessages,
        properties: LimitsProperties,
        alerts: Alerts,
        meters: MeterRegistry,
    ): BotCore = DefaultBotCore(prechecks, limits, queueGate, pipeline, recorder, assembler, messages, properties.queue, alerts, meters)

    @Bean
    fun feedbackService(
        database: Database,
        hasher: UserHasher,
        clock: Clock,
    ): FeedbackService = JdbcFeedbackService(database, hasher, clock)
}
