package shuman.bsu.famcs.rag.testsupport

import shuman.bsu.famcs.rag.assistant.dialog.model.Author
import shuman.bsu.famcs.rag.assistant.dialog.model.ChatSession
import shuman.bsu.famcs.rag.assistant.dialog.model.SessionSummary
import shuman.bsu.famcs.rag.assistant.dialog.model.StoredMessage
import shuman.bsu.famcs.rag.assistant.dialog.repository.ConversationRepository
import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.LlmReply
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.identifier.SessionId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/** Клиент модели для тестов шагов пайплайна: отвечает лямбдой (может приостанавливаться) и записывает все запросы. */
class ScriptedLlmClient(
    private val reply: suspend (LlmRequest) -> String,
) : LlmClient {
    val requests = CopyOnWriteArrayList<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmReply {
        requests += request
        return LlmReply(reply(request), TokenUsage(inputTokens = 10, outputTokens = 5), "fake", "fake-model", 1.milliseconds)
    }

    companion object {
        fun replying(text: String) = ScriptedLlmClient { text }
    }
}

/** Клиент модели, который всегда отказывает, как при падении всех провайдеров. */
class FailingLlmClient : LlmClient {
    var calls = 0

    override suspend fun complete(request: LlmRequest): LlmReply {
        calls++
        throw LlmUnavailableException("тестовый отказ")
    }
}

/** Хранилище диалогов в памяти с теми же правилами сессий, что и в базе. Без синхронизации: тесты работают из одной корутины. */
class InMemoryConversationRepository : ConversationRepository {
    private data class MutableSession(
        val id: Long,
        val userId: TelegramId,
        var summary: String? = null,
        var summarizedUntil: Long = 0,
        val startedAt: Instant,
        var lastActivityAt: Instant,
        var closed: Boolean = false,
    )

    private val sessions = mutableListOf<MutableSession>()
    private val messages = mutableListOf<Pair<Long, StoredMessage>>()
    private var nextSessionId = 1L
    private var nextMessageId = 1L

    val sessionCount: Int get() = sessions.size

    override suspend fun activeSession(
        userId: TelegramId,
        now: Instant,
        idleTimeout: Duration,
    ): ChatSession {
        val active = sessions.firstOrNull { it.userId == userId && !it.closed }
        if (active != null && Duration.between(active.lastActivityAt, now) <= idleTimeout) return active.toSession()
        active?.closed = true
        return MutableSession(nextSessionId++, userId, startedAt = now, lastActivityAt = now).also { sessions += it }.toSession()
    }

    override suspend fun closeSession(
        userId: TelegramId,
        now: Instant,
    ) {
        sessions.firstOrNull { it.userId == userId && !it.closed }?.closed = true
    }

    override suspend fun appendExchange(
        sessionId: SessionId,
        question: String,
        answer: String,
        at: Instant,
    ): Long {
        messages += sessionId.value to StoredMessage(nextMessageId++, Author.USER, question, at)
        val answerId = nextMessageId++
        messages += sessionId.value to StoredMessage(answerId, Author.ASSISTANT, answer, at)
        sessions.first { it.id == sessionId.value }.lastActivityAt = at
        return answerId
    }

    override suspend fun recentMessages(
        sessionId: SessionId,
        limit: Int,
    ): List<StoredMessage> = messages.filter { it.first == sessionId.value }.map { it.second }.takeLast(limit)

    override suspend fun summaryOf(sessionId: SessionId): SessionSummary =
        sessions.first { it.id == sessionId.value }.let { SessionSummary(it.summary, it.summarizedUntil) }

    override suspend fun saveSummary(
        sessionId: SessionId,
        summary: String,
        summarizedUntilMessageId: Long,
    ) {
        sessions.first { it.id == sessionId.value }.apply {
            if (summarizedUntilMessageId > summarizedUntil) {
                this.summary = summary
                summarizedUntil = summarizedUntilMessageId
            }
        }
    }

    override suspend fun deleteMessagesOlderThan(cutoff: Instant): Int {
        val before = messages.size
        messages.removeAll { it.second.createdAt < cutoff }
        return before - messages.size
    }

    private fun MutableSession.toSession() = ChatSession(SessionId(id), userId, summary, summarizedUntil, startedAt, lastActivityAt)
}
