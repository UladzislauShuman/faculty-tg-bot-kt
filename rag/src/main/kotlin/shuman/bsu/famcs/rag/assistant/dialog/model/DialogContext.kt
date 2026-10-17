package shuman.bsu.famcs.rag.assistant.dialog.model

import shuman.bsu.famcs.rag.platform.identifier.SessionId

/** Контекст для обработки вопроса: что помнит бот о текущем диалоге. */
data class DialogContext(
    val sessionId: SessionId,
    val summary: String?,
    /** Последние реплики по порядку, в пределах бюджета токенов. */
    val history: List<DialogTurn>,
) {
    /** Есть ли что-то, на что может ссылаться местоимение в вопросе. */
    val hasMemory: Boolean get() = history.isNotEmpty() || !summary.isNullOrBlank()
}
