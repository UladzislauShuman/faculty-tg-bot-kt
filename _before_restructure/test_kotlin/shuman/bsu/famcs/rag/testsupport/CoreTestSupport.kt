package shuman.bsu.famcs.rag.testsupport

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.ChatId
import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.TokenEstimator
import shuman.bsu.famcs.rag.common.UpdateId
import shuman.bsu.famcs.rag.common.UserHasher
import shuman.bsu.famcs.rag.core.AdminNotifier
import shuman.bsu.famcs.rag.core.Alerts
import shuman.bsu.famcs.rag.core.BotMessages
import shuman.bsu.famcs.rag.core.CoreRequest
import shuman.bsu.famcs.rag.core.MaintenanceSwitch
import shuman.bsu.famcs.rag.dialog.ConsentProperties
import shuman.bsu.famcs.rag.dialog.ContextManager
import shuman.bsu.famcs.rag.dialog.ConversationSummarizer
import shuman.bsu.famcs.rag.dialog.DialogProperties
import shuman.bsu.famcs.rag.dialog.HistoryWindow
import shuman.bsu.famcs.rag.dialog.IdentityQueryRewriter
import shuman.bsu.famcs.rag.dialog.QueryRewriter
import shuman.bsu.famcs.rag.dialog.UserDirectory
import shuman.bsu.famcs.rag.dialog.UserRecord
import shuman.bsu.famcs.rag.feedback.AnswerLog
import shuman.bsu.famcs.rag.feedback.AnswerLogEntry
import shuman.bsu.famcs.rag.intent.ChatHandler
import shuman.bsu.famcs.rag.intent.GreetingMatcher
import shuman.bsu.famcs.rag.intent.Intent
import shuman.bsu.famcs.rag.intent.IntentClassifier
import shuman.bsu.famcs.rag.intent.LlmIntentClassifier
import shuman.bsu.famcs.rag.intent.RagHandler
import shuman.bsu.famcs.rag.intent.RagProperties
import shuman.bsu.famcs.rag.intent.ScheduleLinkHandler
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.RussianTextAnalyzer
import shuman.bsu.famcs.rag.knowledge.store.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.knowledge.store.SearchHit
import shuman.bsu.famcs.rag.limits.AdmissionGate
import shuman.bsu.famcs.rag.limits.BudgetGuard
import shuman.bsu.famcs.rag.limits.InFlightGuard
import shuman.bsu.famcs.rag.limits.LimitsGate
import shuman.bsu.famcs.rag.limits.LimitsProperties
import shuman.bsu.famcs.rag.limits.MinuteWindow
import shuman.bsu.famcs.rag.limits.ProcessedUpdates
import shuman.bsu.famcs.rag.limits.SystemUsage
import shuman.bsu.famcs.rag.limits.UsageStore
import shuman.bsu.famcs.rag.llm.LlmClient
import shuman.bsu.famcs.rag.pipeline.AnswerPipeline
import shuman.bsu.famcs.rag.pipeline.AnswerRecorder
import shuman.bsu.famcs.rag.pipeline.DefaultBotCore
import shuman.bsu.famcs.rag.pipeline.Prechecks
import shuman.bsu.famcs.rag.pipeline.ReplyAssembler
import shuman.bsu.famcs.rag.pipeline.ReplyProperties
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryUsageStore : UsageStore {
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

class InMemoryProcessedUpdates : ProcessedUpdates {
    private val seen = mutableSetOf<Long>()

    override suspend fun registerIfNew(
        updateId: Long,
        at: Instant,
    ) = seen.add(updateId)

    override suspend fun purgeOlderThan(cutoff: Instant) = 0
}

class InMemoryUserDirectory : UserDirectory {
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

class InMemoryAnswerLog : AnswerLog {
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
object TestCore {
    val messages = BotMessages(
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

    val limits = LimitsProperties(
        user = LimitsProperties.User(perMinute = 5, perDay = 20, perDayInEconomy = 10),
        system = LimitsProperties.System(
            messagesPerDay = 500,
            tokensPerDay = 10_000,
            economyThresholdPercent = 70,
            alertThresholdPercent = 80,
        ),
        queue = LimitsProperties.Queue(
            maxConcurrent = 2,
            maxWaiting = 2,
            maxWait = Duration.ofSeconds(60),
            processingTimeout = Duration.ofSeconds(90),
        ),
        updates = LimitsProperties.Updates(maxAge = Duration.ofHours(3), retention = Duration.ofDays(7)),
        input = LimitsProperties.Input(maxQuestionChars = 100),
    )

    val dialog = DialogProperties(
        session = DialogProperties.Session(idleTimeout = Duration.ofHours(2)),
        memory = DialogProperties.Memory(historyBudgetTokens = 1000, maxPairs = 3, summaryMaxChars = 300, maxMessagesRead = 50),
        rewrite = DialogProperties.Rewrite(DialogProperties.RewriteMode.OFF, maxChars = 300),
    )

    val consent = ConsentProperties(version = "v1", text = "Согласие", acceptButton = "Согласен")
    val rag = RagProperties(noInfoMarker = "нет точной информации", maxSources = 3)
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
class CoreHarness(
    scope: CoroutineScope,
    llm: LlmClient,
    hits: List<SearchHit> = listOf(TestCore.hit("practice")),
    val clock: MutableClock = MutableClock(),
    limits: LimitsProperties = TestCore.limits,
    rewriter: QueryRewriter = IdentityQueryRewriter,
    classifier: IntentClassifier? = null,
    reranker: Reranker? = null,
) {
    val users = InMemoryUserDirectory()
    val processed = InMemoryProcessedUpdates()
    val usage = InMemoryUsageStore()
    val answers = InMemoryAnswerLog()
    val conversations = InMemoryConversationStore()
    val notifier = RecordingNotifier()
    val alerts = Alerts(notifier, clock, minInterval = Duration.ofHours(1))
    val maintenance = MaintenanceSwitch(initial = false)
    val reader = StubKnowledgeReader(hits)
    val budget = BudgetGuard(usage, limits.system, clock, notifier)
    private val hasher = UserHasher(TestCore.SECRET, length = 16)
    private val prompts = PromptLibrary()
    private val estimator = TokenEstimator(charsPerToken = 3.0)

    val contexts = ContextManager(
        store = conversations,
        summarizer = ConversationSummarizer(llm, prompts, maxChars = 300),
        window = HistoryWindow(estimator, TestCore.dialog.memory.historyBudgetTokens, TestCore.dialog.memory.maxPairs),
        properties = TestCore.dialog,
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
            RagHandler(retriever, llm, prompts, TestCore.rag, TestCore.messages.noInfo, clock.zone),
            ChatHandler(Intent.SMALLTALK, "smalltalk-system", llm, prompts),
            ChatHandler(Intent.GENERAL, "general-system", llm, prompts),
            ScheduleLinkHandler(TestCore.messages.schedule),
        ),
        meters = SimpleMeterRegistry(),
    )

    val core = DefaultBotCore(
        prechecks = Prechecks(processed, users, maintenance, TestCore.consent, limits.updates, limits.input, TestCore.messages, clock),
        limits = LimitsGate(InFlightGuard(), MinuteWindow(limits.user.perMinute, Duration.ofMinutes(1)), budget, usage, limits.user, clock),
        queueGate = AdmissionGate(limits.queue.maxConcurrent, limits.queue.maxWaiting, limits.queue.maxWait),
        pipeline = pipeline,
        recorder = AnswerRecorder(contexts, answers, usage, hasher, clock),
        assembler = ReplyAssembler(TestCore.messages, TestCore.reply),
        messages = TestCore.messages,
        queue = limits.queue,
        alerts = alerts,
        meters = SimpleMeterRegistry(),
    )

    private var nextUpdate = 1L

    /** Записывает согласие, как после нажатия кнопки в `/start`. */
    suspend fun consent(userId: Long = 1) = users.grantConsent(TelegramId(userId), TestCore.consent.version, clock.instant())

    fun request(
        text: String?,
        userId: Long = 1,
        updateId: Long = nextUpdate++,
        sentAt: Instant = clock.instant(),
    ) = CoreRequest(TelegramId(userId), ChatId(userId), UpdateId(updateId), text, sentAt)
}
