package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import shuman.bsu.famcs.rag.assistant.intent.classifier.GreetingMatcher
import shuman.bsu.famcs.rag.assistant.intent.classifier.implementation.LlmIntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Проверка классификатора веток: старый и новый промпт на одних и тех же запросах.
 *
 * Положительные примеры (ветка RAG) — вопросы набора в том виде, в каком их видит классификатор (после переформулирования, из
 * `-Peval.positives=путь/к/e2e_answers.jsonl`): все они о факультете. Отрицательные — `eval/classifier-cases.yaml` (предварительная разметка
 * Claude). Главная мера — доля вопросов о факультете, ушедших в RAG: ошибка в другую сторону лишает пользователя ответа по документам.
 * Нужен Ollama; модель роли CLASSIFIER берётся из настроек, профиль задаётся `-Peval.profile=ollama-cloud`.
 * Отчёт: `build/reports/eval/classifier.md`.
 */
@Tag("eval")
class ClassifierEvalTest {
    /** Запрос и ожидаемая ветка. */
    private data class Case(
        val text: String,
        val expected: Intent,
    )

    @Test
    fun `старый и новый промпт классификатора на одних запросах`() = runBlocking {
        val positivesPath = Path.of(System.getProperty("eval.positives", "../qa_run_2026-10-20_rerank_no_context/e2e_answers.jsonl"))
        val mapper = JsonMapper()
        val positives = Files.readAllLines(positivesPath).filter { it.isNotBlank() }.map { mapper.readTree(it) }.map { row ->
            val query = row["query"]?.takeUnless { it.isNull }?.asString() ?: row["question"].asString()
            Case(query, Intent.RAG)
        }

        @Suppress("UNCHECKED_CAST")
        val negatives = Files
            .newBufferedReader(Path.of("eval/classifier-cases.yaml"))
            .use { Yaml().load<List<Map<String, String>>>(it) }
            .map { Case(it.getValue("text"), Intent.valueOf(it.getValue("label"))) }
        val cases = positives + negatives
        val llm = EvalLlm.client()

        val oldPrompts = PromptLibrary { path ->
            when (path) {
                "/prompts/base/classifier-system.txt" -> OLD_SYSTEM
                "/prompts/base/classifier-human.txt" -> OLD_HUMAN
                else -> PromptLibrary::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
        val oldResult = run("старый промпт", LlmIntentClassifier(llm, oldPrompts, GreetingMatcher()), cases)
        val newResult = run("новый промпт", LlmIntentClassifier(llm, PromptLibrary(), GreetingMatcher()), cases)

        val text = buildString {
            appendLine("# Классификатор веток: старый и новый промпт (${positives.size} вопросов о факультете, ${negatives.size} прочих)")
            appendLine()
            appendLine(
                "| Промпт | вопросов о факультете → RAG | точность по прочим веткам | GENERAL верно | SMALLTALK верно | SCHEDULE верно |",
            )
            appendLine("|---|---|---|---|---|---|")
            for (r in listOf(oldResult, newResult)) appendLine(r.row)
            appendLine()
            appendLine("## Вопросы о факультете, ушедшие мимо RAG (новый промпт)")
            appendLine()
            appendLine("| Ветка | Запрос |")
            appendLine("|---|---|")
            newResult.misses.forEach { (case, got) -> appendLine("| $got | ${case.text.take(120)} |") }
            appendLine()
            appendLine("## Прочие запросы, классифицированные неверно (новый промпт)")
            appendLine()
            appendLine("| Ожидалось | Получено | Запрос |")
            appendLine("|---|---|---|")
            newResult.wrongNegatives.forEach { (case, got) -> appendLine("| ${case.expected} | $got | ${case.text.take(100)} |") }
        }
        val report = Path.of("build/reports/eval/classifier.md")
        Files.createDirectories(report.parent)
        Files.writeString(report, text)
        println(text.lines().take(6).joinToString("\\n"))
        assertTrue(cases.isNotEmpty())
    }

    /** Итоги прогона одним классификатором. */
    private class Result(
        val row: String,
        val misses: List<Pair<Case, Intent>>,
        val wrongNegatives: List<Pair<Case, Intent>>,
    )

    /** Классифицирует все запросы и считает показатели. */
    private suspend fun run(
        name: String,
        classifier: LlmIntentClassifier,
        cases: List<Case>,
    ): Result {
        val results = cases.map { it to classifier.classify(it.text) }
        val positives = results.filter { it.first.expected == Intent.RAG }
        val negatives = results.filter { it.first.expected != Intent.RAG }

        fun pct(part: Int, whole: Int) = if (whole == 0) "—" else "%.0f%%".format(100.0 * part / whole)

        fun byLabel(label: Intent) = negatives.filter { it.first.expected == label }.let {
            pct(
                it.count { (c, g) -> g == c.expected },
                it.size,
            )
        }
        val row = "| $name | ${pct(positives.count { it.second == Intent.RAG }, positives.size)} | " +
            "${pct(
                negatives.count { (c, g) ->
                    g == c.expected
                },
                negatives.size,
            )} | ${byLabel(Intent.GENERAL)} | ${byLabel(Intent.SMALLTALK)} | ${byLabel(Intent.SCHEDULE)} |"
        return Result(row, positives.filter { it.second != Intent.RAG }, negatives.filter { (c, g) -> g != c.expected })
    }

    private companion object {
        const val OLD_SYSTEM =
            "Ты классификатор намерений пользователя бота ФПМИ БГУ. Ответь ровно одним словом из набора: RAG, SCHEDULE, SMALLTALK, GENERAL. Без пояснений."

        val OLD_HUMAN = """
            Категории:
            - RAG: фактические вопросы именно о факультете ФПМИ БГУ: учёба, преподаватели, деканат, поступление, баллы, правила, документы, практика, а также уточняющие вопросы по уже обсуждаемым фактам.
            - SCHEDULE: вопросы про расписание занятий: какая пара, когда занятия, расписание группы или преподавателя, звонки, аудитории.
            - SMALLTALK: приветствия, прощания, благодарности, вопросы к самому боту («что ты умеешь», «как дела»).
            - GENERAL: общие вопросы не по теме факультета: математика, программирование, погода, анекдоты, рассуждения.
            Если сомневаешься между RAG и любой другой категорией, выбери RAG.

            Сообщение: {query}
            Класс:
        """.trimIndent()
    }
}
