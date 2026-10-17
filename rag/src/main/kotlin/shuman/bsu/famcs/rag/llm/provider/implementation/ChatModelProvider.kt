package shuman.bsu.famcs.rag.llm.provider.implementation

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.exception.AuthenticationException
import dev.langchain4j.exception.ContentFilteredException
import dev.langchain4j.exception.HttpException
import dev.langchain4j.exception.InternalServerException
import dev.langchain4j.exception.InvalidRequestException
import dev.langchain4j.exception.LangChain4jException
import dev.langchain4j.exception.ModelNotFoundException
import dev.langchain4j.exception.RateLimitException
import dev.langchain4j.exception.UnsupportedFeatureException
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ChatRequestParameters
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import shuman.bsu.famcs.rag.llm.exception.LlmAuthenticationException
import shuman.bsu.famcs.rag.llm.exception.LlmException
import shuman.bsu.famcs.rag.llm.exception.LlmQuotaExhaustedException
import shuman.bsu.famcs.rag.llm.exception.LlmRateLimitException
import shuman.bsu.famcs.rag.llm.exception.LlmRequestRejectedException
import shuman.bsu.famcs.rag.llm.exception.LlmServerException
import shuman.bsu.famcs.rag.llm.exception.LlmTimeoutException
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.ProviderCall
import shuman.bsu.famcs.rag.llm.model.ProviderReply
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import shuman.bsu.famcs.rag.llm.provider.LlmProvider
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import dev.langchain4j.exception.TimeoutException as LangChainTimeoutException

private val log = KotlinLogging.logger {}

/**
 * Провайдер на базе [ChatModel] из LangChain4j.
 *
 * Отвечает за три вещи: собрать запрос библиотеки из наших сообщений, выполнить блокирующий вызов так, чтобы
 * отмена корутины прерывала его, и перевести ошибки библиотеки в [LlmException].
 * Модель, температура и лимит ответа задаются для каждого вызова, поэтому один клиент обслуживает все роли.
 */
class ChatModelProvider(
    override val name: String,
    private val chatModel: ChatModel,
    /** Диспетчер для блокирующего вызова. В тестах подменяется. */
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmProvider {
    override suspend fun chat(call: ProviderCall): ProviderReply {
        val request = ChatRequest
            .builder()
            .messages(call.messages.map(::toChatMessage))
            .parameters(
                ChatRequestParameters
                    .builder()
                    .modelName(call.model)
                    .temperature(call.temperature)
                    .topP(call.topP)
                    .maxOutputTokens(call.maxOutputTokens)
                    .build(),
            ).build()

        val response = try {
            // runInterruptible: при отмене корутины (таймаут, остановка) поток блокирующего вызова прерывается.
            runInterruptible(blockingDispatcher) { chatModel.chat(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw translate(e)
        }

        val usage = response.tokenUsage()?.let { used ->
            val input = used.inputTokenCount()
            val output = used.outputTokenCount()
            if (input != null && output != null) TokenUsage(input, output) else null
        }
        return ProviderReply(
            text = response.aiMessage().text().orEmpty(),
            usage = usage,
            model = response.modelName() ?: call.model,
        )
    }

    /** Переводит наше сообщение в тип сообщения LangChain4j. */
    private fun toChatMessage(message: LlmMessage): ChatMessage = when (message.author) {
        LlmMessage.Author.SYSTEM -> SystemMessage.from(message.content)
        LlmMessage.Author.USER -> UserMessage.from(message.content)
        LlmMessage.Author.ASSISTANT -> AiMessage.from(message.content)
    }

    /** Перевод исключений библиотеки в наши. Решение принимается по смыслу ошибки (таймаут, лимит, сбой сервера). */
    internal fun translate(e: Exception): LlmException {
        // Текст ошибки библиотеки в журнал не идёт: это тело ответа провайдера, а оно может цитировать запрос пользователя
        // (ошибка разбора, превышение контекста). В сообщении остаются класс ошибки и код ответа; тело - на уровне отладки.
        val status = (e as? HttpException)?.statusCode()?.let { ", HTTP $it" }.orEmpty()
        val label = "Провайдер '$name': ${e.javaClass.simpleName}$status"
        log.debug { "Тело ошибки провайдера '$name' (первые 300 символов): ${e.message?.take(ERROR_BODY_LIMIT)}" }
        return when {
            e is LangChainTimeoutException || hasCause<HttpTimeoutException>(e) || hasCause<SocketTimeoutException>(e) -> {
                LlmTimeoutException(label, e)
            }

            e is RateLimitException -> {
                LlmRateLimitException(label, e)
            }

            e is AuthenticationException -> {
                LlmAuthenticationException(label, e)
            }

            e is InvalidRequestException || e is ModelNotFoundException ||
                e is ContentFilteredException || e is UnsupportedFeatureException -> {
                LlmRequestRejectedException(label, e)
            }

            e is HttpException -> {
                translateHttpStatus(e.statusCode(), label, e)
            }

            e is InternalServerException || e is LangChain4jException -> {
                LlmServerException(label, e)
            }

            else -> {
                LlmServerException(label, e)
            }
        }
    }

    /**
     * Переводит HTTP-код ответа провайдера в наше исключение: 402 — квота, 401/403 — ключ, 408 — таймаут, 429 — лимит частоты,
     * остальные 4xx — запрос отклонён, 5xx — сбой сервера.
     *
     * @param status код ответа.
     * @param label короткое описание для сообщения (без тела ответа).
     * @param cause исходное исключение.
     */
    private fun translateHttpStatus(
        status: Int,
        label: String,
        cause: Throwable,
    ): LlmException = when (status) {
        HTTP_PAYMENT_REQUIRED -> LlmQuotaExhaustedException(label, cause)
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> LlmAuthenticationException(label, cause)
        HTTP_REQUEST_TIMEOUT -> LlmTimeoutException(label, cause)
        HTTP_TOO_MANY_REQUESTS -> LlmRateLimitException(label, cause)
        in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR -> LlmRequestRejectedException(label, cause)
        else -> LlmServerException(label, cause)
    }

    /**
     * Ищет в цепочке причин ошибки исключение нужного типа.
     *
     * @param e исключение, с которого начинается поиск.
     * @return `true`, если среди причин есть исключение типа [T].
     */
    private inline fun <reified T : Throwable> hasCause(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is T) return true
            val next = current.cause
            current = if (next === current) null else next
        }
        return false
    }

    private companion object {
        const val ERROR_BODY_LIMIT = 300
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_PAYMENT_REQUIRED = 402
        const val HTTP_FORBIDDEN = 403
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
