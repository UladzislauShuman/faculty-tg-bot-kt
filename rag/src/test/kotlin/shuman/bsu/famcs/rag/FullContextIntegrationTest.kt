package shuman.bsu.famcs.rag

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.qdrant.QdrantContainer
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.onnx.OnnxSession
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseWriter
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeCollections
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.testsupport.HashEmbeddingProvider
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import shuman.bsu.famcs.rag.testsupport.TestKnowledge
import shuman.bsu.famcs.rag.testsupport.TestPostgres
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Весь контекст приложения на настоящих PostgreSQL и Qdrant (нужен Docker). Заглушены только то, что дорого или внешнее:
 * ONNX-модели (вместо них хеш-векторы) и языковые модели (вместо них заданные ответы).
 *
 * Проверяет сборку: что все настройки привязываются, бины собираются, миграции накатываются, а путь вопроса проходит
 * через HTTP-вход, ядро, поиск, журнал, оценки и пробу здоровья.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.main.allow-bean-definition-overriding=true",
        "bot.http.enabled=true",
        "bot.http.api-key=test-key-0123456789-abcdef",
        "bot.privacy.hash-secret=test-secret-0123456789-abcdef",
        "bot.knowledge.embedding.dimension=4",
        "bot.knowledge.reranker.enabled=false",
        "bot.knowledge.indexing.index-on-empty-start=false",
        "bot.knowledge.indexing.cron=-",
        "bot.retention.cron=-",
        "bot.health.min-knowledge-chunks=1",
        "bot.limits.user.per-minute=50",
    ],
)
@Import(FullContextIntegrationTest.FakeModels::class)
class FullContextIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = TestPostgres.container()

        @Container
        @JvmStatic
        val qdrant = QdrantContainer("qdrant/qdrant:v1.17.1")

        @DynamicPropertySource
        @JvmStatic
        fun containers(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("bot.knowledge.qdrant.host", qdrant::getHost)
            registry.add("bot.knowledge.qdrant.grpc-port") { qdrant.getMappedPort(6334) }
        }
    }

    /** Подставные модели: вместо файлов ONNX — заглушка и хеш-векторы, вместо провайдеров LLM — заданные ответы. */
    @TestConfiguration
    class FakeModels {
        @Bean
        fun embeddingModel(): OnnxSession = mockk(relaxed = true)

        @Bean
        fun embeddingProvider(): EmbeddingProvider = HashEmbeddingProvider()

        @Bean
        fun llmClient(): LlmClient = ScriptedLlmClient {
            when (it.role) {
                LlmRole.CLASSIFIER -> "RAG"
                LlmRole.GENERATOR -> "Практика проходит осенью."
                else -> "Здравствуйте!"
            }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var collections: KnowledgeCollections

    @Autowired
    private lateinit var writer: KnowledgeBaseWriter

    @Autowired
    private lateinit var embeddings: EmbeddingProvider

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var json: JsonMapper

    private val http = HttpClient.newHttpClient()

    private fun post(
        path: String,
        body: String,
        apiKey: String? = "test-key-0123456789-abcdef",
    ): HttpResponse<String> {
        val request = HttpRequest
            .newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .apply { apiKey?.let { header("X-Api-Key", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun seedKnowledge() = runBlocking {
        collections.ensureActive()
        val question = "Когда практика?"
        writer.replaceDocument(
            null,
            TestKnowledge.document(
                "https://fpmi.bsu.by/practice",
                1,
                TestKnowledge.chunk(
                    "https://fpmi.bsu.by/practice",
                    "Практика проходит осенью.",
                    embeddings.embedQuery(question),
                    intArrayOf(5),
                ),
            ),
        )
    }

    private fun parse(response: HttpResponse<String>): JsonNode = json.readTree(response.body())

    @Test
    fun `вопрос проходит весь путь - ответ с источником, запись в журнал, оценка и проба здоровья`() {
        seedKnowledge()

        val answer = post("/api/v1/ask", """{"userId": 4242, "text": "Когда практика?"}""")
        assertEquals(200, answer.statusCode(), answer.body())
        val reply = parse(answer)
        assertEquals("ANSWERED", reply.get("status").asString())
        assertTrue(reply.get("text").asString().startsWith("Практика проходит осенью."))
        assertEquals(
            "https://fpmi.bsu.by/practice",
            reply
                .get("sources")
                .get(0)
                .get("url")
                .asString(),
        )
        val answerId = reply.get("answerId").longValue()

        val logged = jdbc
            .sql("SELECT count(*) FROM answer_log WHERE id = :id AND route = 'RAG' AND status = 'ANSWERED'")
            .param("id", answerId)
            .query(Int::class.java)
            .single()
        assertEquals(1, logged)
        val storedMessages = jdbc
            .sql(
                "SELECT count(*) FROM message m JOIN chat_session s ON s.id = m.session_id WHERE s.telegram_id = 4242",
            ).query(Int::class.java)
            .single()
        assertEquals(2, storedMessages, "в диалог записаны вопрос и ответ")

        val rating = post("/api/v1/feedback", """{"userId": 4242, "answerId": $answerId, "rating": 5}""")
        assertEquals(200, rating.statusCode(), rating.body())
        assertTrue(parse(rating).get("saved").booleanValue())

        val health = get("/actuator/health")
        assertEquals(200, health.statusCode(), health.body())
        assertEquals("UP", parse(health).get("status").asString())
        assertTrue("knowledge" in health.body() && "db" in health.body())
    }

    @Test
    fun `запрос без ключа или с чужим ключом отклоняется`() {
        assertEquals(401, post("/api/v1/ask", """{"userId": 1, "text": "привет"}""", apiKey = null).statusCode())
        assertEquals(401, post("/api/v1/ask", """{"userId": 1, "text": "привет"}""", apiKey = "wrong-key-0123456789-abcdef").statusCode())
    }

    @Test
    fun `метрики Prometheus доступны и содержат счётчик запросов бота`() {
        seedKnowledge()
        post("/api/v1/ask", """{"userId": 4343, "text": "Когда практика?"}""")

        val metrics = get("/actuator/prometheus")

        assertEquals(200, metrics.statusCode())
        assertTrue("bot_requests_total" in metrics.body())
    }
}
