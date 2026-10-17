package shuman.bsu.famcs.rag.assistant.dialog.model

/** Сводка сессии и граница, до которой она дошла. */
data class SessionSummary(
    val text: String?,
    /** Сообщения с номерами до этого уже вошли в [text]. */
    val summarizedUntilMessageId: Long,
)
