package shuman.bsu.famcs.rag.llm.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.llm.configuration.properties.LlmProperties
import shuman.bsu.famcs.rag.llm.exception.LlmAuthenticationException
import shuman.bsu.famcs.rag.llm.exception.LlmException
import shuman.bsu.famcs.rag.llm.exception.LlmRateLimitException
import shuman.bsu.famcs.rag.llm.exception.LlmRequestRejectedException
import shuman.bsu.famcs.rag.llm.exception.LlmServerException
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.ProviderCall
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import shuman.bsu.famcs.rag.llm.provider.implementation.ChatModelProvider
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Что реально уходит по сети провайдеру с OpenAI-совместимым интерфейсом (YandexGPT, DeepSeek, Qwen) и как разбираются его ответы.
 * Вместо провайдера — локальный HTTP-сервер: тест проверяет формат запроса и перевод кодов ответа в наши ошибки.
 */
class OpenAiCompatibleWireTest {
    private class Captured(val path: String, val headers: Map<String, String>, val body: String)

    private val captured = CopyOnWriteArrayList<Captured>()
    private var server: HttpServer? = null
    private val json = JsonMapper()

    private fun startServer(respond: (HttpExchange) -> Pair<Int, String>): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            captured += Captured(
                path = exchange.requestURI.path,
                headers = exchange.requestHeaders.entries.associate { (key, values) -> key.lowercase() to values.first() },
                body = body,
            )
            val (status, payload) = respond(exchange)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://localhost:${http.address.port}/v1"
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun provider(
        baseUrl: String,
        requestBody: Map<String, Any> = emptyMap(),
    ): ChatModelProvider {
        val properties = LlmProperties.ProviderProperties(
            type = LlmProperties.ProviderType.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            apiKey = "test-api-key",
            defaultModel = "gpt://folder/yandexgpt-lite/latest",
            httpTimeout = Duration.ofSeconds(5),
            maxConcurrentCalls = 1,
            headers = mapOf("x-folder-id" to "folder-42"),
            requestBody = requestBody,
            ollama = null,
        )
        return ChatModelProvider("yandex", ChatModelFactory.create("yandex", properties))
    }

    private val call = ProviderCall(
        model = "gpt://folder/yandexgpt-lite/latest",
        messages = listOf(
            LlmMessage.system("Ты бот"),
            LlmMessage.user("Привет"),
            LlmMessage.assistant("Здравствуйте"),
            LlmMessage.user("Как дела?"),
        ),
        temperature = 0.3,
        topP = 0.9,
        maxOutputTokens = 77,
    )

    private val okBody = """
        {"id":"1","object":"chat.completion","created":1,"model":"yandexgpt-lite",
         "choices":[{"index":0,"message":{"role":"assistant","content":"Привет!"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}
    """.trimIndent()

    @Test
    fun `запрос идёт на chat completions с ключом, заголовком каталога и параметрами генерации`() = runTest {
        val reply = provider(startServer { 200 to okBody }).chat(call)

        assertEquals("Привет!", reply.text)
        assertEquals(TokenUsage(12, 3), reply.usage)

        val request = captured.single()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-api-key", request.headers["authorization"])
        assertEquals("folder-42", request.headers["x-folder-id"])

        val body = json.readTree(request.body)
        assertEquals("gpt://folder/yandexgpt-lite/latest", body.get("model").asString())
        assertEquals(0.3, body.get("temperature").doubleValue())
        assertEquals(0.9, body.get("top_p").doubleValue())
        val messages = body.get("messages")
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            (0 until messages.size()).map { messages.get(it).get("role").asString() },
        )
        // Какое имя параметра длины ответа отправляется, важно для провайдера: зафиксировано, чтобы смена библиотеки не прошла молча.
        val limitField = listOf("max_tokens", "max_completion_tokens").filter { body.has(it) }
        assertEquals(1, limitField.size, "ровно одно поле лимита ответа: $limitField")
        assertEquals("max_tokens", limitField.single(), "LangChain4j 1.15 отправляет max_tokens")
        assertEquals(77, body.get(limitField.single()).intValue())
    }

    @Test
    fun `дополнительные поля тела запроса уходят провайдеру, в том числе вложенные`() = runTest {
        val extra = mapOf("thinking" to mapOf("type" to "disabled"))

        provider(startServer { 200 to okBody }, requestBody = extra).chat(call)

        val body = json.readTree(captured.single().body)
        assertEquals("disabled", body.get("thinking").get("type").asString(), "отключение рассуждений DeepSeek дошло до сервера")
    }

    @Test
    fun `без дополнительных полей тело запроса стандартное`() = runTest {
        provider(startServer { 200 to okBody }).chat(call)

        assertTrue(!json.readTree(captured.single().body).has("thinking"))
    }

    @Test
    fun `коды ответа провайдера переводятся в наши ошибки`() = runTest {
        suspend fun errorFor(status: Int): LlmException {
            captured.clear()
            server?.stop(0)
            val url = startServer { status to """{"error":{"message":"сбой $status","type":"x"}}""" }
            return assertFailsWith<LlmException> { provider(url).chat(call) }
        }

        assertTrue(errorFor(429) is LlmRateLimitException)
        assertTrue(errorFor(401) is LlmAuthenticationException)
        assertTrue(errorFor(500) is LlmServerException)
        assertTrue(errorFor(400) is LlmRequestRejectedException)
    }
}
