package shuman.bsu.famcs.rag.llm

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

/** Вызов не уложился в таймаут. */
class LlmTimeoutException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = true
}

/** Сбой на стороне провайдера: 5xx, обрыв соединения, недоступный хост. */
class LlmServerException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = true
}

/** Провайдер ограничил частоту запросов (HTTP 429). Повтор там же только увеличит нагрузку: идём к резерву. */
class LlmRateLimitException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
}

/** Исчерпаны квота или деньги. Провайдер откладывается на долгую паузу, пока человек не вмешается. */
class LlmQuotaExhaustedException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
}

/** Ключ не принят. Это ошибка настройки, повтор бесполезен. */
class LlmAuthenticationException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
}

/** Провайдер отклонил сам запрос (неверная модель, слишком длинный контекст, фильтр). Не сбой провайдера. */
class LlmRequestRejectedException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
    override val countsAsProviderFailure = false
}

/** Модель вернула пустой, слишком короткий или зацикленный ответ. Его нельзя показывать пользователю. */
class LlmBadResponseException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = true
}

/** Провайдер сейчас не принимает вызовы: ограничитель разомкнут. Это не новый сбой, идём к следующему в цепочке. */
class LlmProviderUnavailableException(message: String, cause: Throwable? = null) : LlmException(message, cause) {
    override val retryable = false
    override val countsAsProviderFailure = false
}

/** Все провайдеры цепочки отказали. Ядро превращает это в ответ «перегрузка». */
class LlmUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
