package shuman.bsu.famcs.rag.assistant.limit.service.implementation

import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.limit.model.Admission
import shuman.bsu.famcs.rag.assistant.limit.model.BudgetMode
import shuman.bsu.famcs.rag.assistant.limit.model.Denial
import shuman.bsu.famcs.rag.assistant.limit.repository.UsageRepository
import shuman.bsu.famcs.rag.assistant.limit.service.BudgetGuard
import shuman.bsu.famcs.rag.assistant.limit.service.InFlightGuard
import shuman.bsu.famcs.rag.assistant.limit.service.MinuteWindow
import shuman.bsu.famcs.rag.assistant.limit.service.RequestLimiter
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Clock
import java.time.LocalDate

/**
 * Лимиты на вопрос: один за раз, бюджет системы, суточный лимит пользователя, не более N в минуту.
 *
 * Порядок от дешёвого к дорогому, а запись в минутное окно идёт последней: отказанный вопрос место в окне не занимает.
 * Место «в процессе» всегда освобождается, что бы ни случилось с работой.
 */
class RequestLimiterImpl(
    private val inFlight: InFlightGuard,
    private val minute: MinuteWindow,
    private val budget: BudgetGuard,
    private val usage: UsageRepository,
    private val limits: LimitProperties.User,
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

    /**
     * Находит причину отказа: бюджет системы, суточный лимит (в режиме экономии он ниже), минутный лимит.
     *
     * @param userId Telegram ID пользователя.
     * @return причина отказа или `null`, если вопрос можно принять.
     */
    private suspend fun denialFor(userId: TelegramId): Denial? {
        val mode = budget.mode()
        if (mode == BudgetMode.EXHAUSTED) return Denial.SystemBudget

        val dayLimit = if (mode == BudgetMode.ECONOMY) limits.perDayInEconomy else limits.perDay
        if (usage.userMessages(userId, LocalDate.now(clock)) >= dayLimit) return Denial.DayLimit(dayLimit)

        return minute.tryAcquire(userId, clock.instant())?.let { Denial.MinuteLimit(it) }
    }
}
