package shuman.bsu.famcs.rag.llm.exception

/** Провайдер сейчас не принимает вызовы: ограничитель разомкнут. Это не новый сбой, идём к следующему в цепочке. */
class LlmProviderUnavailableException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
    override val countsAsProviderFailure = false
}
