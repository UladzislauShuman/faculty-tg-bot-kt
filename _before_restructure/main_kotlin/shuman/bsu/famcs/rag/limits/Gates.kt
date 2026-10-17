package shuman.bsu.famcs.rag.limits

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import shuman.bsu.famcs.rag.common.TelegramId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.toKotlinDuration

/** Причина отказа до начала обработки вопроса. */
sealed interface Denial {
    /** Предыдущий вопрос того же человека ещё обрабатывается. */
    data object Busy : Denial

    /** Больше вопросов за минуту, чем разрешено. Повторить можно через [retryAfter]. */
    data class MinuteLimit(val retryAfter: Duration) : Denial

    /** Исчерпан суточный лимит пользователя. */
    data class DayLimit(val limit: Int) : Denial

    /** Исчерпан суточный бюджет системы. */
    data object SystemBudget : Denial
}

/** Лимиты на пользователя: допускает вопрос к обработке или называет причину отказа. */
interface RequestLimiter {
    suspend fun <T> withAdmission(
        userId: TelegramId,
        block: suspend () -> T,
    ): Admission<T>
}

/** Ограниченная очередь работы: место либо выдаётся, либо вопрос получает [OverloadedException]. */
interface WorkQueue {
    suspend fun <T> withSlot(block: suspend () -> T): T
}

/** Результат попытки выполнить работу под защитой лимитов. */
sealed interface Admission<out T> {
    data class Denied(val denial: Denial) : Admission<Nothing>

    data class Completed<T>(val value: T) : Admission<T>
}

/** Очередь вопросов не приняла или не дождалась своего места. Ядро превращает это в ответ «перегрузка». */
class OverloadedException(message: String) : RuntimeException(message)

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

/** Не более одного вопроса за раз от одного пользователя: иначе один человек занимает несколько мест в очереди. */
class InFlightGuard {
    private val active = ConcurrentHashMap.newKeySet<Long>()

    fun tryEnter(userId: TelegramId): Boolean = active.add(userId.value)

    fun leave(userId: TelegramId) {
        active.remove(userId.value)
    }
}

/**
 * Лимиты на вопрос: один за раз, бюджет системы, суточный лимит пользователя, не более N в минуту.
 *
 * Порядок от дешёвого к дорогому, а запись в минутное окно идёт последней: отказанный вопрос место в окне не занимает.
 * Место «в процессе» всегда освобождается, что бы ни случилось с работой.
 */
class LimitsGate(
    private val inFlight: InFlightGuard,
    private val minute: MinuteWindow,
    private val budget: BudgetGuard,
    private val usage: UsageStore,
    private val limits: LimitsProperties.User,
    private val clock: Clock,
) : RequestLimiter {
    override suspend fun <T> withAdmission(
        userId: TelegramId,
        block: suspend () -> T,
    ): Admission<T> {
        if (!inFlight.tryEnter(userId)) return Admission.Denied(Denial.Busy)
        try {
            denialFor(userId)?.let { return Admission.Denied(it) }
            return Admission.Completed(block())
        } finally {
            inFlight.leave(userId)
        }
    }

    private suspend fun denialFor(userId: TelegramId): Denial? {
        val mode = budget.mode()
        if (mode == BudgetMode.EXHAUSTED) return Denial.SystemBudget

        val dayLimit = if (mode == BudgetMode.ECONOMY) limits.perDayInEconomy else limits.perDay
        if (usage.userMessages(userId, LocalDate.now(clock)) >= dayLimit) return Denial.DayLimit(dayLimit)

        return minute.tryAcquire(userId, clock.instant())?.let { Denial.MinuteLimit(it) }
    }
}

/**
 * Ограниченная очередь вопросов: одновременно работают [maxConcurrent], ещё [maxWaiting] ждут не дольше [maxWait].
 * Остальные получают отказ сразу: быстрый честный отказ лучше, чем ответ через пять минут или упавший процесс.
 */
class AdmissionGate(
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
