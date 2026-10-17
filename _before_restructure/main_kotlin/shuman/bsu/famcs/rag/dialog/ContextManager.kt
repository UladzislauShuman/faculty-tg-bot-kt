package shuman.bsu.famcs.rag.dialog

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import shuman.bsu.famcs.rag.common.TelegramId
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Менеджер контекста: решает, что бот помнит о диалоге при ответе на очередной вопрос.
 *
 * Память двухслойная:
 * - последние реплики дословно, в пределах бюджета токенов ([HistoryWindow]);
 * - краткая сводка всего, что в окно не поместилось (создаётся в фоне, пользователь её не ждёт).
 *
 * Сводка строится на модели с ролью SUMMARIZER и не блокирует ответ: если она запаздывает, часть старых реплик
 * временно выпадает из промпта, но ответ не задерживается.
 */
class ContextManager(
    private val store: ConversationStore,
    private val summarizer: ConversationSummarizer,
    private val window: HistoryWindow,
    private val properties: DialogProperties,
    private val clock: Clock,
    /** Область для фоновых сводок. Владелец — приложение: сводка переживает обработку одного сообщения. */
    private val scope: CoroutineScope,
) {
    /** Сессии, для которых сводка строится прямо сейчас. Вторая параллельная сводка той же сессии только впустую тратила бы токены. */
    private val compacting: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Контекст для вопроса пользователя: активная сессия (при необходимости новая), сводка и свежие реплики. */
    suspend fun open(userId: TelegramId): DialogContext {
        val session = store.activeSession(userId, clock.instant(), properties.session.idleTimeout)
        val messages = store.recentMessages(session.id, properties.memory.maxMessagesRead)
        return DialogContext(
            sessionId = session.id,
            summary = session.summary,
            history = window.select(messages).kept.map(StoredMessage::toTurn),
        )
    }

    /** Начинает новый диалог (`/new`): текущая сессия закрывается, контекст забывается. */
    suspend fun reset(userId: TelegramId) = store.closeSession(userId, clock.instant())

    /**
     * Сохраняет обмен вопросом и ответом и, если история переросла окно, запускает сводку в фоне.
     * Возвращает номер сообщения-ответа: к нему привязывается запись журнала.
     */
    suspend fun record(
        context: DialogContext,
        question: String,
        answer: String,
    ): Long {
        val answerMessageId = store.appendExchange(context.sessionId, question, answer, clock.instant())
        scheduleCompaction(context)
        return answerMessageId
    }

    private suspend fun scheduleCompaction(context: DialogContext) {
        val sessionKey = context.sessionId.value
        val session = store.recentMessages(context.sessionId, properties.memory.maxMessagesRead)
        val overflow = window.select(session).overflow
        if (overflow.isEmpty() || !compacting.add(sessionKey)) return

        scope.launch(CoroutineName("summary-$sessionKey")) {
            try {
                compact(context, overflow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Не удалось обновить сводку сессии $sessionKey" }
            } finally {
                compacting.remove(sessionKey)
            }
        }
    }

    private suspend fun compact(
        context: DialogContext,
        overflow: List<StoredMessage>,
    ) {
        // Сводку нужно дополнять только тем, что в неё ещё не вошло: иначе одни и те же сообщения учитываются дважды.
        val fresh = store.summaryOf(context.sessionId)
        val unsummarized = overflow.filter { it.id > fresh.summarizedUntilMessageId }
        if (unsummarized.isEmpty()) return

        val summary = summarizer.summarize(fresh.text, unsummarized) ?: return
        store.saveSummary(context.sessionId, summary, unsummarized.last().id)
        log.debug { "Сводка сессии ${context.sessionId.value} обновлена: ${unsummarized.size} сообщений" }
    }
}
