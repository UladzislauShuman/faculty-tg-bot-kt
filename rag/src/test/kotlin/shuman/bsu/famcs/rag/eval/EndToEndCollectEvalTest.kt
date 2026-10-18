package shuman.bsu.famcs.rag.eval

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.eval.model.E2eRow
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertTrue

/**
 * Сквозной прогон, шаг 1 из 2: все вопросы набора задаются запущенному боту через HTTP-вход с трассой.
 *
 * Результат пишется в `build/reports/eval/e2e_answers.jsonl` и разбирается вторым шагом ([EndToEndJudgeEvalTest]).
 * Шаги разделены намеренно: бот с моделями и судья на локальной модели вместе не помещаются в память.
 * Одиночные вопросы идут от разных тестовых пользователей, шаги сценария — от одного (у него общая история).
 * Нужны: бот с `BOT_HTTP_ENABLED=true`, адрес в `-Peval.botUrl` (по умолчанию http://localhost:8080), ключ в переменной среды `EVAL_API_KEY`.
 */
@Tag("eval")
class EndToEndCollectEvalTest {
    private val mapper: JsonMapper = jacksonMapperBuilder().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Test
    fun `вопросы набора проходят через бота`() {
        val baseUrl = System.getProperty("eval.botUrl", "http://localhost:8080")
        val apiKey = requireNotNull(System.getenv("EVAL_API_KEY")) { "Задайте EVAL_API_KEY: ключ HTTP-входа бота" }
        val cases = QaSetLoader.load(Path.of(System.getProperty("eval.qaSet", "eval/qa-set.yaml")))
        // Свежие номера пользователей: чужая история и суточные лимиты прошлых прогонов не мешают.
        val base = System.currentTimeMillis() / 1000 * 1000
        val scenarioUsers = cases
            .mapNotNull { it.scenario }
            .distinct()
            .withIndex()
            .associate { (i, name) -> name to base + i }

        val out = Path.of("build/reports/eval/e2e_answers.jsonl")
        Files.createDirectories(out.parent)
        Files.newBufferedWriter(out).use { writer ->
            for (case in cases) {
                val userId = case.scenario?.let { scenarioUsers.getValue(it) } ?: (base + 500 + case.id)
                val row = ask(baseUrl, apiKey, userId, case)
                writer.write(mapper.writeValueAsString(row))
                writer.newLine()
                writer.flush()
            }
        }
        assertTrue(Files.size(out) > 0)
    }

    /** Задаёт один вопрос боту; при сбое связи возвращает строку со статусом `CLIENT_ERROR`, чтобы прогон не прерывался. */
    private fun ask(
        baseUrl: String,
        apiKey: String,
        userId: Long,
        case: shuman.bsu.famcs.rag.eval.model.EvalCase,
    ): E2eRow = try {
        val body = mapper.writeValueAsString(mapOf("userId" to userId, "text" to case.question))
        val request = HttpRequest
            .newBuilder(URI.create("$baseUrl/api/v1/ask?trace=true"))
            .timeout(Duration.ofSeconds(400))
            .header("Content-Type", "application/json")
            .header("X-Api-Key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val tree = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
        val trace = tree["trace"]
        val passages = trace?.get("passages")?.toList().orEmpty()
        E2eRow(
            id = case.id,
            scenario = case.scenario,
            step = case.step,
            question = case.question,
            reference = case.referenceAnswer,
            expectedUrl = case.expectedUrl,
            status = tree["status"].asString(),
            answer = tree["text"].asString().substringBefore("\n\nИсточники:").trim(),
            route = trace?.get("route")?.asString(),
            query = trace?.get("query")?.asString(),
            passageUrls = passages.map { it["url"].asString() },
            passageTexts = passages.map { it["text"].asString() },
            elapsedMs = tree["elapsedMs"].asLong(),
            model = trace?.get("model")?.takeUnless { it.isNull }?.asString(),
            candidates = trace?.get("candidates")?.toList().orEmpty().map {
                "${it["url"].asString()}|${it["relevance"]?.asString()}|${it["selected"].asString()}"
            },
            searchMs = trace?.get("searchMs")?.asLong() ?: 0,
            rerankMs = trace?.get("rerankMs")?.asLong() ?: 0,
            generationMs = trace?.get("generationMs")?.asLong() ?: 0,
        )
    } catch (e: Exception) {
        E2eRow(
            case.id,
            case.scenario,
            case.step,
            case.question,
            case.referenceAnswer,
            case.expectedUrl,
            "CLIENT_ERROR",
            e.message.orEmpty(),
            null,
            null,
            emptyList(),
            emptyList(),
            0,
            null,
        )
    }
}
