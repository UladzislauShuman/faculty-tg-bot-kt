package shuman.bsu.famcs.rag.llm.exception

/**
 * Ошибки слоя LLM.
 *
 * Зачем свои типы: клиенты HTTP и библиотеки бросают разные исключения. На границе провайдера они переводятся
 * в этот набор, и цепочка (повтор, резерв, ограничитель) принимает решения по смыслу ошибки, а не по её классу.
 */
sealed class LlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Можно ли повторить вызов у того же провайдера. Повтор — только при временном сбое. */
    abstract val retryable: Boolean

    /** Считать ли ошибку сбоем провайдера для ограничителя. Неверный запрос — наша ошибка, провайдер не виноват. */
    open val countsAsProviderFailure: Boolean get() = true
}
