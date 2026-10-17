package shuman.bsu.famcs.rag.assistant.limit.model

/** Суточный расход всей системы. */
data class SystemUsage(
    val messages: Int,
    val tokens: Long,
)
