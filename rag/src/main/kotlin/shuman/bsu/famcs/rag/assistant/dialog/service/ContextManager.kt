package shuman.bsu.famcs.rag.assistant.dialog.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import shuman.bsu.famcs.rag.api.ConversationService
import shuman.bsu.famcs.rag.assistant.configuration.properties.DialogProperties
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.dialog.model.StoredMessage
import shuman.bsu.famcs.rag.assistant.dialog.repository.ConversationRepository
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
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
    private val conversations: ConversationRepository,
    private val summarizer: ConversationSummarizer,
    private val window: HistoryWindow,
    private val properties: DialogProperties,
    private val clock: Clock,
    /** Область для фоновых сводок. Владелец — приложение: сводка переживает обработку одного сообщения. */
    private val scope: CoroutineScope,
) : ConversationService {
    /** Сессии, для которых сводка строится прямо сейчас. Вторая параллельная сводка той же сессии только впустую тратила бы токены. */
    private val compacting: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Контекст для вопроса пользователя: активная сессия (при необходимости новая), сводка и свежие реплики. */
    suspend fun open(userId: TelegramId): DialogContext {
        val session = conversations.activeSession(userId, clock.instant(), properties.session.idleTimeout)
        val messages = conversations.recentMessages(session.id, properties.memory.maxMessagesRead)
        return DialogContext(
            sessionId = session.id,
            summary = session.summary,
            history = window.select(messages).kept.map(StoredMessage::toTurn),
        )
    }

    /** Начинает новый диалог (`/new`): текущая сессия закрывается, контекст забывается. */
    override suspend fun reset(userId: TelegramId) = conversations.closeSession(userId, clock.instant())

    /**
     * Сохраняет обмен вопросом и ответом и, если история переросла окно, запускает сводку в фоне.
     * Возвращает номер сообщения-ответа: к нему привязывается запись журнала.
     */
    suspend fun record(
        context: DialogContext,
        question: String,
        answer: String,
    ): Long {
        val answerMessageId = conversations.appendExchange(context.sessionId, question, answer, clock.instant())
        scheduleCompaction(context)
        return answerMessageId
    }

    /**
     * Запускает в фоне обновление сводки, если история переросла окно. Для одной сессии сводка строится не больше одной за раз.
     *
     * @param context контекст, с которым был задан вопрос.
     */
    private suspend fun scheduleCompaction(context: DialogContext) {
        val sessionKey = context.sessionId.value
        val session = conversations.recentMessages(context.sessionId, properties.memory.maxMessagesRead)
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

    /**
     * Дополняет сводку сообщениями, которые в неё ещё не вошли, и сохраняет её.
     *
     * @param context контекст сессии.
     * @param overflow сообщения, не поместившиеся в окно истории.
     */
    private suspend fun compact(
        context: DialogContext,
        overflow: List<StoredMessage>,
    ) {
        // Сводку нужно дополнять только тем, что в неё ещё не вошло: иначе одни и те же сообщения учитываются дважды.
        val fresh = conversations.summaryOf(context.sessionId)
        val unsummarized = overflow.filter { it.id > fresh.summarizedUntilMessageId }
        if (unsummarized.isEmpty()) return

        val summary = summarizer.summarize(fresh.text, unsummarized) ?: return
        conversations.saveSummary(context.sessionId, summary, unsummarized.last().id)
        log.debug { "Сводка сессии ${context.sessionId.value} обновлена: ${unsummarized.size} сообщений" }
    }
}
