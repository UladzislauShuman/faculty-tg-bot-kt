package shuman.bsu.famcs.rag.assistant.limit.model

import java.time.Duration

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
