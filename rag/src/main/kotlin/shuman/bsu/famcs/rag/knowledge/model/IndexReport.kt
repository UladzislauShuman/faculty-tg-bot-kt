package shuman.bsu.famcs.rag.knowledge.model

/** Итог запуска индексации. */
data class IndexReport(
    val runId: Long,
    val mode: IndexMode,
    val status: IndexRunStatus,
    val counters: IndexCounters,
    val message: String?,
)
