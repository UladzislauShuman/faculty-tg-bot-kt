package shuman.bsu.famcs.rag.llm.exception

/** Провайдер отклонил сам запрос (неверная модель, слишком длинный контекст, фильтр). Не сбой провайдера. */
class LlmRequestRejectedException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
    override val countsAsProviderFailure = false
}
