package shuman.bsu.famcs.rag.llm.exception

/** Ключ не принят. Это ошибка настройки, повтор бесполезен. */
class LlmAuthenticationException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
}
