package shuman.bsu.famcs.rag.knowledge.indexing.repository

import shuman.bsu.famcs.rag.knowledge.model.IndexCounters
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.model.IndexRunStatus
import java.time.Instant

/** Журнал запусков индексации. Номер запуска служит версией данных в Qdrant. */
interface IndexRunRepository {
    /**
     * Записывает начало запуска.
     *
     * @param mode режим индексации.
     * @param at момент начала.
     * @return номер запуска.
     */
    suspend fun start(
        mode: IndexMode,
        at: Instant,
    ): Long

    /**
     * Записывает окончание запуска.
     *
     * @param id номер запуска.
     * @param status итоговый статус.
     * @param counters счётчики страниц.
     * @param message пояснение или `null`.
     * @param at момент окончания.
     */
    suspend fun finish(
        id: Long,
        status: IndexRunStatus,
        counters: IndexCounters,
        message: String?,
        at: Instant,
    )
}
