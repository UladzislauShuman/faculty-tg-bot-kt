package shuman.bsu.famcs.rag.assistant.limit.service

import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Скользящее окно «не больше N вопросов за минуту» для каждого пользователя. Хранится в памяти:
 * потеря окна при перезапуске безвредна, а запрос к базе на каждый вопрос не нужен.
 */
class MinuteWindow(
    private val perMinute: Int,
    private val window: Duration,
) {
    private val hits = ConcurrentHashMap<Long, ArrayDeque<Instant>>()

    /** Забывает пользователей без вопросов за последнюю минуту. Вызывается периодически, чтобы карта не росла вместе с числом пользователей. */
    fun evictIdle(now: Instant) {
        hits.entries.removeIf { (_, queue) -> synchronized(queue) { queue.isEmpty() || Duration.between(queue.last(), now) >= window } }
    }

    /** Записывает вопрос, если лимит не превышен. Возвращает `null` при успехе или время до освобождения места. */
    fun tryAcquire(
        userId: TelegramId,
        now: Instant,
    ): Duration? {
        val queue = hits.computeIfAbsent(userId.value) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && Duration.between(queue.first(), now) >= window) queue.removeFirst()
            if (queue.size >= perMinute) return Duration.between(now, queue.first().plus(window)).coerceAtLeast(Duration.ofSeconds(1))
            queue.addLast(now)
            return null
        }
    }
}
