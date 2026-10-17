package shuman.bsu.famcs.rag.knowledge.model

/** Итоги запуска индексации. */
data class IndexCounters(
    val found: Int,
    val changed: Int,
    val failed: Int,
    val removed: Int,
)
