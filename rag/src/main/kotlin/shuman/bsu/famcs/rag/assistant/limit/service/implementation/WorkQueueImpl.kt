package shuman.bsu.famcs.rag.assistant.limit.service.implementation

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import shuman.bsu.famcs.rag.assistant.limit.exception.OverloadedException
import shuman.bsu.famcs.rag.assistant.limit.service.WorkQueue
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.toKotlinDuration

/**
 * Ограниченная очередь вопросов: одновременно работают [maxConcurrent], ещё [maxWaiting] ждут не дольше [maxWait].
 * Остальные получают отказ сразу: быстрый честный отказ лучше, чем ответ через пять минут или упавший процесс.
 */
class WorkQueueImpl(
    maxConcurrent: Int,
    private val maxWaiting: Int,
    private val maxWait: Duration,
) : WorkQueue {
    private val maxParallel = maxConcurrent
    private val slots = Semaphore(maxConcurrent)
    private val waiting = AtomicInteger()

    /** Число ожидающих в очереди. Для метрики. */
    val waitingCount: Int get() = waiting.get()

    /** Сколько мест занято работой. Для метрики. */
    val inUse: Int get() = maxParallel - slots.availablePermits

    override suspend fun <T> withSlot(block: suspend () -> T): T {
        if (!slots.tryAcquire()) waitForSlot()
        try {
            return block()
        } finally {
            slots.release()
        }
    }

    /**
     * Занимает место в очереди. Если ожидающих уже слишком много или место не освободилось за `maxWait`, бросает исключение.
     *
     * @throws OverloadedException очередь полна или время ожидания вышло.
     */
    private suspend fun waitForSlot() {
        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet()
            throw OverloadedException("Очередь полна: ждут $maxWaiting")
        }
        var acquired = false
        try {
            // Таймаут ограничивает ожидание, а решение принимается по флагу: если место успели выдать в момент срабатывания
            // таймаута, оно не теряется (иначе число мест очереди уменьшалось бы навсегда).
            withTimeoutOrNull(maxWait.toKotlinDuration()) {
                slots.acquire()
                acquired = true
            }
        } finally {
            waiting.decrementAndGet()
        }
        if (!acquired) throw OverloadedException("Место в очереди не освободилось за $maxWait")
    }
}
