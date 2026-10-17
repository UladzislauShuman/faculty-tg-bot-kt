package shuman.bsu.famcs.rag.llm.exception

/** Вызов не уложился в таймаут. */
class LlmTimeoutException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = true
}
