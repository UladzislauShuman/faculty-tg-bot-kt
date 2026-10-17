package shuman.bsu.famcs.rag.llm.exception

/** Исчерпаны квота или деньги. Провайдер откладывается на долгую паузу, пока человек не вмешается. */
class LlmQuotaExhaustedException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
}
