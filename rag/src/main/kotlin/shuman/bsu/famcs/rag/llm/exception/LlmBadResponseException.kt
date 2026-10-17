package shuman.bsu.famcs.rag.llm.exception

/** Модель вернула пустой, слишком короткий или зацикленный ответ. Его нельзя показывать пользователю. */
class LlmBadResponseException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = true
}
