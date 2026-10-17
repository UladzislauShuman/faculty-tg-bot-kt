package shuman.bsu.famcs.rag.assistant.limit.repository

import java.time.Instant

/** Номера уже обработанных обновлений Telegram: повторная доставка не обрабатывается и не тратит токены. */
interface ProcessedUpdateRepository {
    /** Регистрирует номер. `true` — обновление новое, `false` — уже обрабатывалось. */
    suspend fun registerIfNew(
        updateId: Long,
        at: Instant,
    ): Boolean

    /** Удаляет записи старше [cutoff]. Возвращает число удалённых. */
    suspend fun purgeOlderThan(cutoff: Instant): Int
}
