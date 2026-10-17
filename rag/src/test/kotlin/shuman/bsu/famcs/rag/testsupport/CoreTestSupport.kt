package shuman.bsu.famcs.rag.testsupport

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.api.model.RagRequest
import shuman.bsu.famcs.rag.api.model.UserRecord
import shuman.bsu.famcs.rag.assistant.configuration.properties.BotMessagesProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.DialogProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.RagHandlerProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.ReplyProperties
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.QueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation.IdentityQueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.service.ContextManager
import shuman.bsu.famcs.rag.assistant.dialog.service.ConversationSummarizer
import shuman.bsu.famcs.rag.assistant.dialog.service.HistoryWindow
import shuman.bsu.famcs.rag.assistant.feedback.model.AnswerLogEntry
import shuman.bsu.famcs.rag.assistant.feedback.repository.AnswerLogRepository
import shuman.bsu.famcs.rag.assistant.intent.classifier.GreetingMatcher
import shuman.bsu.famcs.rag.assistant.intent.classifier.IntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.classifier.implementation.LlmIntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.handler.implementation.ChatHandler
import shuman.bsu.famcs.rag.assistant.intent.handler.implementation.RagHandler
import shuman.bsu.famcs.rag.assistant.intent.handler.implementation.ScheduleLinkHandler
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.assistant.limit.model.SystemUsage
import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.assistant.limit.repository.UsageRepository
import shuman.bsu.famcs.rag.assistant.limit.service.BudgetGuard
import shuman.bsu.famcs.rag.assistant.limit.service.InFlightGuard
import shuman.bsu.famcs.rag.assistant.limit.service.MinuteWindow
import shuman.bsu.famcs.rag.assistant.limit.service.implementation.RequestLimiterImpl
import shuman.bsu.famcs.rag.assistant.limit.service.implementation.WorkQueueImpl
import shuman.bsu.famcs.rag.assistant.pipeline.service.AnswerPipeline
import shuman.bsu.famcs.rag.assistant.pipeline.service.AnswerRecorder
import shuman.bsu.famcs.rag.assistant.pipeline.service.MaintenanceSwitch
import shuman.bsu.famcs.rag.assistant.pipeline.service.Prechecks
import shuman.bsu.famcs.rag.assistant.pipeline.service.ReplyAssembler
import shuman.bsu.famcs.rag.assistant.pipeline.service.implementation.RagServiceImpl
import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.model.SearchHit
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.RussianTextAnalyzer
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.identifier.UpdateId
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import shuman.bsu.famcs.rag.platform.notification.Alerts
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.platform.text.TokenEstimator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryUsageRepository : UsageRepository {
    val userMessages = mutableMapOf<Pair<Long, LocalDate>, Int>()
    val systemMessages = mutableMapOf<LocalDate, Int>()
    val systemTokens = mutableMapOf<LocalDate, Long>()

    override suspend fun userMessages(
        userId: TelegramId,
        day: LocalDate,
    ) = userMessages[userId.value to day] ?: 0

    override suspend fun recordMessage(
        userId: TelegramId,
        day: LocalDate,
        tokens: Long,
    ) {
        userMessages.merge(userId.value to day, 1, Int::plus)
        systemMessages.merge(day, 1, Int::plus)
    }

    override suspend fun addSystemTokens(
        day: LocalDate,
        tokens: Long,
    ): Long = systemTokens.merge(day, tokens, Long::plus)!!

    override suspend fun system(day: LocalDate) = SystemUsage(systemMessages[day] ?: 0, systemTokens[day] ?: 0)
}

class InMemoryProcessedUpdateRepository : ProcessedUpdateRepository {
    private val seen = mutableSetOf<Long>()

    override suspend fun registerIfNew(
        updateId: Long,
        at: Instant,
    ) = seen.add(updateId)

    override suspend fun purgeOlderThan(cutoff: Instant) = 0
}

class InMemoryUserService : UserService {
    private val consents = mutableMapOf<Long, String?>()
    val forgotten = mutableListOf<TelegramId>()
    val blocked = mutableListOf<TelegramId>()

    override suspend fun visit(
        userId: TelegramId,
        at: Instant,
    ): UserRecord {
        consents.putIfAbsent(userId.value, null)
        return UserRecord(consents[userId.value], at.takeIf { consents[userId.value] != null })
    }

    override suspend fun grantConsent(
        userId: TelegramId,
        version: String,
        at: Instant,
    ) {
        consents[userId.value] = version
    }

    override suspend fun markBlocked(userId: TelegramId) {
        blocked += userId
    }

    override suspend fun forget(userId: TelegramId) {
        forgotten += userId
        consents.remove(userId.value)
    }
}

class InMemoryAnswerLogRepository : AnswerLogRepository {
    val entries = CopyOnWriteArrayList<AnswerLogEntry>()
    var failing = false

    override suspend fun append(entry: AnswerLogEntry): AnswerId {
        check(!failing) { "тестовый сбой журнала" }
        entries += entry
        return AnswerId(entries.size.toLong())
    }
}

class RecordingNotifier : AdminNotifier {
    val messages = CopyOnWriteArrayList<String>()

    override suspend fun notifyAdmins(text: String) {
        messages += text
    }
}

/** Хранилище знаний, которое всегда отдаёт заданные находки. */
class StubKnowledgeReader(var hits: List<SearchHit>) : KnowledgeBaseReader {
    override suspend fun search(request: HybridSearchRequest) = hits

    override suspend fun chunkCount() = hits.size.toLong()
}

/** Настройки ядра для тестов: числа выбраны так, чтобы границы легко проверялись. */
object TestRag {
    val messages = BotMessagesProperties(
        noInfo = "В базе нет информации.",
        noInfoHint = "Загляните на сайт.",
        schedule = "Расписание: https://fpmi.bsu.by/schedule",
        rateLimitedMinute = "Подождите {seconds} с.",
        rateLimitedDay = "Лимит {limit} в сутки.",
        busy = "Я ещё отвечаю на предыдущий вопрос.",
        overloaded = "Перегрузка.",
        budgetExhausted = "Бюджет на сегодня исчерпан.",
        error = "Ошибка.",
        consentRequired = "Нужно согласие.",
        unsupportedInput = "Только текст.",
        tooLong = "Не длиннее {limit} символов.",
        maintenance = "Обслуживание.",
        sourcesTitle = "Источники:",
        checkedOn = "проверено {date}",
        dateCaveat = "Проверьте актуальность.",
    )

    val limits = LimitProperties(
        user = LimitProperties.User(perMinute = 5, perDay = 20, perDayInEconomy = 10),
        system = LimitProperties.System(
            messagesPerDay = 500,
            tokensPerDay = 10_000,
            economyThresholdPercent = 70,
            alertThresholdPercent = 80,
        ),
        queue = LimitProperties.Queue(
            maxConcurrent = 2,
            maxWaiting = 2,
            maxWait = Duration.ofSeconds(60),
            processingTimeout = Duration.ofSeconds(90),
        ),
        updates = LimitProperties.Updates(maxAge = Duration.ofHours(3), retention = Duration.ofDays(7)),
        input = LimitProperties.Input(maxQuestionChars = 100),
    )

    val dialog = DialogProperties(
        session = DialogProperties.Session(idleTimeout = Duration.ofHours(2)),
        memory = DialogProperties.Memory(historyBudgetTokens = 1000, maxPairs = 3, summaryMaxChars = 300, maxMessagesRead = 50),
        rewrite = DialogProperties.Rewrite(DialogProperties.RewriteMode.OFF, maxChars = 300),
    )

    val consent = ConsentProperties(version = "v1", text = "Согласие", acceptButton = "Согласен")
    val rag = RagHandlerProperties(noInfoMarker = "нет точной информации", maxSources = 3)
    val reply = ReplyProperties(dateCaveatPattern = "срок|\\d{1,2}\\.\\d{1,2}", titleSeparator = " | ")
    const val SECRET = "test-secret-0123456789"

    fun hit(
        parent: String,
        text: String = "Текст раздела $parent",
    ) = SearchHit(
        id = "c-$parent",
        score = 0.5,
        text = "фрагмент $parent",
        parentId = parent,
        parentText = text,
        url = "https://fpmi.bsu.by/$parent",
        title = "Страница $parent",
        headings = emptyMap(),
        indexedAt = Instant.parse("2026-10-07T03:00:00Z"),
    )
}

/**
 * Собранное ядро с подставными зависимостями. Модель задаётся лямбдой по роли: тест описывает, что ответит каждая роль.
 * Все хранилища в памяти, так что тест проверяет логику ядра, а не базу.
 */
class RagHarness(
    scope: CoroutineScope,
    llm: LlmClient,
    hits: List<SearchHit> = listOf(TestRag.hit("practice")),
    val clock: MutableClock = MutableClock(),
    limits: LimitProperties = TestRag.limits,
    rewriter: QueryRewriter = IdentityQueryRewriter,
    classifier: IntentClassifier? = null,
    reranker: Reranker? = null,
) {
    val users = InMemoryUserService()
    val processed = InMemoryProcessedUpdateRepository()
    val usage = InMemoryUsageRepository()
    val answers = InMemoryAnswerLogRepository()
    val conversations = InMemoryConversationRepository()
    val notifier = RecordingNotifier()
    val alerts = Alerts(notifier, clock, minInterval = Duration.ofHours(1))
    val maintenance = MaintenanceSwitch(initial = false)
    val reader = StubKnowledgeReader(hits)
    val budget = BudgetGuard(usage, limits.system, clock, notifier)
    private val hasher = UserHasher(TestRag.SECRET, length = 16)
    private val prompts = PromptLibrary()
    private val estimator = TokenEstimator(charsPerToken = 3.0)

    val contexts = ContextManager(
        conversations = conversations,
        summarizer = ConversationSummarizer(llm, prompts, maxChars = 300),
        window = HistoryWindow(estimator, TestRag.dialog.memory.historyBudgetTokens, TestRag.dialog.memory.maxPairs),
        properties = TestRag.dialog,
        clock = clock,
        scope = scope,
    )

    private val retriever = KnowledgeRetriever(
        embeddings = HashEmbeddingProvider(),
        sparse = Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
        reader = reader,
        reranker = reranker,
        topN = 3,
        meters = SimpleMeterRegistry(),
    )

    private val pipeline = AnswerPipeline(
        contexts = contexts,
        rewriter = rewriter,
        classifier = classifier ?: LlmIntentClassifier(llm, prompts, GreetingMatcher()),
        handlers = listOf(
            RagHandler(retriever, llm, prompts, TestRag.rag, TestRag.messages.noInfo, clock.zone),
            ChatHandler(Intent.SMALLTALK, "smalltalk-system", llm, prompts),
            ChatHandler(Intent.GENERAL, "general-system", llm, prompts),
            ScheduleLinkHandler(TestRag.messages.schedule),
        ),
        meters = SimpleMeterRegistry(),
    )

    val core = RagServiceImpl(
        prechecks = Prechecks(processed, users, maintenance, TestRag.consent, limits.updates, limits.input, TestRag.messages, clock),
        limits = RequestLimiterImpl(
            InFlightGuard(),
            MinuteWindow(limits.user.perMinute, Duration.ofMinutes(1)),
            budget,
            usage,
            limits.user,
            clock,
        ),
        queueGate = WorkQueueImpl(limits.queue.maxConcurrent, limits.queue.maxWaiting, limits.queue.maxWait),
        pipeline = pipeline,
        recorder = AnswerRecorder(contexts, answers, usage, hasher, clock),
        assembler = ReplyAssembler(TestRag.messages, TestRag.reply),
        messages = TestRag.messages,
        queue = limits.queue,
        alerts = alerts,
        meters = SimpleMeterRegistry(),
    )

    private var nextUpdate = 1L

    /** Записывает согласие, как после нажатия кнопки в `/start`. */
    suspend fun consent(userId: Long = 1) = users.grantConsent(TelegramId(userId), TestRag.consent.version, clock.instant())

    fun request(
        text: String?,
        userId: Long = 1,
        updateId: Long = nextUpdate++,
        sentAt: Instant = clock.instant(),
    ) = RagRequest(TelegramId(userId), ChatId(userId), UpdateId(updateId), text, sentAt)
}
