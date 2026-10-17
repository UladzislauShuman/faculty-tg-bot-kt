package shuman.bsu.famcs.rag.llm.provider

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.exception.AuthenticationException
import dev.langchain4j.exception.HttpException
import dev.langchain4j.exception.InvalidRequestException
import dev.langchain4j.exception.RateLimitException
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.TokenUsage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.llm.exception.LlmAuthenticationException
import shuman.bsu.famcs.rag.llm.exception.LlmException
import shuman.bsu.famcs.rag.llm.exception.LlmQuotaExhaustedException
import shuman.bsu.famcs.rag.llm.exception.LlmRateLimitException
import shuman.bsu.famcs.rag.llm.exception.LlmRequestRejectedException
import shuman.bsu.famcs.rag.llm.exception.LlmServerException
import shuman.bsu.famcs.rag.llm.exception.LlmTimeoutException
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.ProviderCall
import shuman.bsu.famcs.rag.llm.provider.implementation.ChatModelProvider
import shuman.bsu.famcs.rag.platform.metrics.timed
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.langchain4j.exception.TimeoutException as LangChainTimeoutException

class ChatModelProviderTest {
    private val call = ProviderCall(
        model = "yandexgpt-lite",
        messages = listOf(LlmMessage.system("Ты бот ФПМИ"), LlmMessage.user("Привет")),
        temperature = 0.1,
        topP = 0.8,
        maxOutputTokens = 64,
    )

    private fun providerThatReturns(
        text: String,
        usage: TokenUsage? = null,
        capture: (ChatRequest) -> Unit = {},
    ) = ChatModelProvider(
        name = "test",
        chatModel = object : ChatModel {
            override fun doChat(chatRequest: ChatRequest): ChatResponse {
                capture(chatRequest)
                return ChatResponse
                    .builder()
                    .aiMessage(AiMessage.from(text))
                    .modelName("echo-model")
                    .tokenUsage(usage)
                    .build()
            }
        },
    )

    private fun providerThatThrows(error: Exception) = ChatModelProvider(
        name = "test",
        chatModel = object : ChatModel {
            override fun doChat(chatRequest: ChatRequest): ChatResponse = throw error
        },
    )

    @Test
    fun `запрос собирается из наших сообщений с параметрами вызова`() = runTest {
        var captured: ChatRequest? = null
        val provider = providerThatReturns("Здравствуйте", capture = { captured = it })

        provider.chat(call)

        val request = requireNotNull(captured)
        assertIs<SystemMessage>(request.messages()[0])
        assertIs<UserMessage>(request.messages()[1])
        assertEquals("yandexgpt-lite", request.modelName())
        assertEquals(0.1, request.temperature())
        assertEquals(0.8, request.topP())
        assertEquals(64, request.maxOutputTokens())
    }

    @Test
    fun `ответ и расход токенов переносятся в ответ провайдера`() = runTest {
        val provider = providerThatReturns("Здравствуйте", usage = TokenUsage(40, 7))

        val reply = provider.chat(call)

        assertEquals("Здравствуйте", reply.text)
        assertEquals(
            shuman.bsu.famcs.rag.llm.model
                .TokenUsage(40, 7),
            reply.usage,
        )
        assertEquals("echo-model", reply.model)
    }

    @Test
    fun `если провайдер не сообщил токены, usage равен null`() = runTest {
        assertNull(providerThatReturns("Здравствуйте", usage = null).chat(call).usage)
    }

    @Test
    fun `ошибки библиотеки переводятся в ошибки слоя LLM по смыслу`() = runTest {
        suspend fun translated(error: Exception) = assertFailsWith<LlmException> { providerThatThrows(error).chat(call) }

        assertIs<LlmTimeoutException>(translated(LangChainTimeoutException("read timed out")))
        assertIs<LlmRateLimitException>(translated(RateLimitException("слишком часто")))
        assertIs<LlmAuthenticationException>(translated(AuthenticationException("ключ")))
        assertIs<LlmRequestRejectedException>(translated(InvalidRequestException("неверная модель")))
        assertIs<LlmQuotaExhaustedException>(translated(HttpException(402, "нет денег")))
        assertIs<LlmRateLimitException>(translated(HttpException(429, "квота")))
        assertIs<LlmServerException>(translated(HttpException(503, "недоступен")))
        assertIs<LlmServerException>(translated(IllegalStateException("неожиданно")))
    }

    @Test
    fun `таймаут в цепочке причин определяется как таймаут`() = runTest {
        val error = RuntimeException("обёртка", java.net.http.HttpTimeoutException("request timed out"))
        assertIs<LlmTimeoutException>(assertFailsWith<LlmException> { providerThatThrows(error).chat(call) })
    }

    @Test
    fun `тело ошибки провайдера не попадает в сообщение исключения`() {
        val provider = providerThatThrows(HttpException(400, "тело ответа цитирует вопрос: СЕКРЕТНЫЙ_МАРКЕР"))

        val error = provider.translate(HttpException(400, "тело ответа цитирует вопрос: СЕКРЕТНЫЙ_МАРКЕР"))

        assertIs<LlmRequestRejectedException>(error)
        assertTrue("СЕКРЕТНЫЙ_МАРКЕР" !in error.message.orEmpty(), "сообщение: ${error.message}")
        assertTrue("HTTP 400" in error.message.orEmpty())
    }
}
