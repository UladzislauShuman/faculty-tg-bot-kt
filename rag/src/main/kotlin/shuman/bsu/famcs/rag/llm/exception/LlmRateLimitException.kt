package shuman.bsu.famcs.rag.llm.exception

/** Провайдер ограничил частоту запросов (HTTP 429). Повтор там же только увеличит нагрузку: идём к резерву. */
class LlmRateLimitException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
}
