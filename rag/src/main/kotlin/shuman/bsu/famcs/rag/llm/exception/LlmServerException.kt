package shuman.bsu.famcs.rag.llm.exception

/** Сбой на стороне провайдера: 5xx, обрыв соединения, недоступный хост. */
class LlmServerException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = true
}
