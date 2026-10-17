package shuman.bsu.famcs.rag.assistant.dialog.repository

import shuman.bsu.famcs.rag.assistant.dialog.model.ChatSession
import shuman.bsu.famcs.rag.assistant.dialog.model.SessionSummary
import shuman.bsu.famcs.rag.assistant.dialog.model.StoredMessage
import shuman.bsu.famcs.rag.platform.identifier.SessionId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Duration
import java.time.Instant

/**
 * Хранилище диалогов: сессии, сообщения и сводка старой части диалога.
 *
 * Правило сессий: у пользователя не больше одной активной сессии (это гарантирует индекс в базе).
 * Сессия закрывается после паузы дольше `idleTimeout` или командой `/new`, и тогда контекст начинается с чистого листа.
 */
interface ConversationRepository {
    /** Возвращает активную сессию. Если её нет или пользователь молчал дольше [idleTimeout], закрывает старую и открывает новую. */
    suspend fun activeSession(
        userId: TelegramId,
        now: Instant,
        idleTimeout: Duration,
    ): ChatSession

    /** Закрывает активную сессию (`/new`). Если её нет, ничего не делает. */
    suspend fun closeSession(
        userId: TelegramId,
        now: Instant,
    )

    /** Сохраняет вопрос и ответ одной транзакцией и обновляет время активности сессии. Возвращает номер сообщения-ответа. */
    suspend fun appendExchange(
        sessionId: SessionId,
        question: String,
        answer: String,
        at: Instant,
    ): Long

    /** Последние [limit] сообщений сессии, от старых к новым. */
    suspend fun recentMessages(
        sessionId: SessionId,
        limit: Int,
    ): List<StoredMessage>

    /** Актуальная сводка сессии. Читается из базы заново: фоновая сводка могла обновиться с момента открытия сессии. */
    suspend fun summaryOf(sessionId: SessionId): SessionSummary

    /** Записывает сводку и номер последнего сообщения, которое в неё вошло. Старую сводку заменяет. */
    suspend fun saveSummary(
        sessionId: SessionId,
        summary: String,
        summarizedUntilMessageId: Long,
    )

    /** Удаляет сообщения старше [cutoff] (срок хранения). Возвращает число удалённых. */
    suspend fun deleteMessagesOlderThan(cutoff: Instant): Int
}
