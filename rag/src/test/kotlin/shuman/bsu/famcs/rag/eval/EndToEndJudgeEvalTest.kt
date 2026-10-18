package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.eval.model.E2eRow
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCase
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCriterion
import shuman.bsu.famcs.rag.quality.judge.model.Verdict
import shuman.bsu.famcs.rag.quality.judge.service.implementation.LlmAnswerJudge
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Сквозной прогон, шаг 2 из 2: судья оценивает ответы, собранные [EndToEndCollectEvalTest], и считает итоги.
 *
 * Для каждого ответа три проверки: корректность (против эталона), верность (против найденных разделов) и релевантность (против вопроса).
 * Вина за неверный ответ разделяется грубо: если слов эталона нет в найденных разделах (меньше [FOUND] от значимых слов), виноват
 * поиск; если есть, а ответ неверен, виновата генерация. Это приближение: слова, а не смысл.
 * Отчёт: `build/reports/eval/e2e_report.md`, оценки по вопросам: `e2e_judged.jsonl`. Нужен запущенный Ollama с моделью роли JUDGE.
 */
@Tag("eval")
class EndToEndJudgeEvalTest {
    private val mapper: JsonMapper = jacksonMapperBuilder().build()

    /** Строка ответа с вердиктами судьи и пометкой вины. */
    private data class Judged(
        val row: E2eRow,
        val correct: Verdict,
        val faithful: Verdict?,
        val relevant: Verdict,
        val found: Double,
        val pageHit: Boolean,
    ) {
        /** Ответ неверен, а нужное в разделах было: ошибка генерации. */
        val generationFault: Boolean get() = correct == Verdict.FAIL && found >= FOUND

        /** Ответ неверен, и нужного в найденных разделах нет: ошибка поиска (или данных). */
        val retrievalFault: Boolean get() = correct == Verdict.FAIL && found < FOUND
    }

    @Test
    fun `судья оценивает сквозной прогон`() = runBlocking {
        val rows = Files
            .readAllLines(Path.of("build/reports/eval/e2e_answers.jsonl"))
            .filter {
                it.isNotBlank()
            }.map { mapper.readValue<E2eRow>(it) }
        val judge = LlmAnswerJudge(EvalLlm.client(), PromptLibrary())

        val judged = rows.map { row ->
            val answered = row.status == "ANSWERED" || row.status == "NO_INFO"
            val case = JudgeCase(row.question, row.answer, row.reference, row.passageTexts)
            val correct = if (answered) judge.judge(JudgeCriterion.CORRECTNESS, case).verdict else Verdict.FAIL
            val faithful = if (answered && row.passageTexts.isNotEmpty()) judge.judge(JudgeCriterion.FAITHFULNESS, case).verdict else null
            val relevant = if (answered) judge.judge(JudgeCriterion.RELEVANCE, case).verdict else Verdict.FAIL
            Judged(
                row,
                correct,
                faithful,
                relevant,
                AnswerCoverage.share(row.reference, row.passageTexts.joinToString("\n")),
                row.expectedUrl in row.passageUrls,
            )
        }

        val dir = Path.of("build/reports/eval")
        Files.writeString(dir.resolve("e2e_judged.jsonl"), judged.joinToString("\n") { mapper.writeValueAsString(it.toMap()) })
        val report = render(judged)
        Files.writeString(dir.resolve("e2e_report.md"), report)
        println(report.lines().take(14).joinToString("\n"))
        assertTrue(judged.isNotEmpty())
    }

    /** Плоское представление строки для файла оценок. */
    private fun Judged.toMap() = mapOf(
        "id" to row.id,
        "scenario" to row.scenario,
        "step" to row.step,
        "question" to row.question,
        "status" to row.status,
        "correct" to correct,
        "faithful" to faithful,
        "relevant" to relevant,
        "found" to "%.2f".format(found),
        "pageHit" to pageHit,
        "answer" to row.answer,
        "reference" to row.reference,
    )

    /** Итоговый отчёт: сводка, разделение вины, спад по шагам сценария и список неверных ответов. */
    private fun render(judged: List<Judged>): String = buildString {
        val n = judged.size.toDouble()

        fun pct(count: Int) = "%.0f%%".format(100 * count / n)

        fun rate(
            list: List<Judged>,
            pick: (Judged) -> Verdict?,
        ): String {
            val votes = list.mapNotNull(pick)
            return if (votes.isEmpty()) "—" else "%.0f%% (из ${votes.size})".format(100.0 * votes.count { it == Verdict.PASS } / votes.size)
        }
        val answered = judged.filter { it.row.status == "ANSWERED" }
        val elapsed = judged.map { it.row.elapsedMs }.sorted()
        appendLine("# Сквозной прогон: ${judged.size} вопросов")
        appendLine()
        appendLine("| Показатель | Значение |")
        appendLine("|---|---|")
        appendLine("| Корректность (судья, против эталона) | ${rate(judged) { it.correct }} |")
        appendLine("| Верность (судья, против найденных разделов; только ответы с поиском) | ${rate(judged) { it.faithful }} |")
        appendLine("| Релевантность (судья) | ${rate(judged) { it.relevant }} |")
        appendLine("| Ответов «не нашёл» | ${pct(judged.count { it.row.status == "NO_INFO" })} |")
        appendLine("| Ошибки и перегрузка | ${pct(judged.count { it.row.status !in setOf("ANSWERED", "NO_INFO") })} |")
        appendLine("| Неверных из-за поиска (слов эталона нет в разделах) | ${pct(judged.count { it.retrievalFault })} |")
        appendLine("| Неверных из-за генерации (нужное было в разделах) | ${pct(judged.count { it.generationFault })} |")
        appendLine("| Страница из `url` набора в найденных (занижено) | ${pct(judged.count { it.pageHit })} |")
        appendLine(
            "| Время ответа p50 / p95, с | ${elapsed.getOrNull(
                elapsed.size / 2,
            )?.div(1000)} / ${elapsed.getOrNull((elapsed.size * 95) / 100)?.div(1000)} |",
        )
        appendLine("| Ответов со статусом ANSWERED | ${answered.size} |")
        appendLine()
        appendLine("## Корректность по шагу сценария (одиночные вопросы — шаг 0 отдельной строкой)")
        appendLine()
        appendLine("| Шаг | Вопросов | Корректность |")
        appendLine("|---|---|---|")
        appendLine(
            "| одиночные | ${judged.count { it.row.scenario == null }} | ${rate(
                judged.filter { it.row.scenario == null },
            ) { it.correct }} |",
        )
        judged.filter { it.row.scenario != null }.groupBy { it.row.step }.toSortedMap().forEach { (step, list) ->
            appendLine("| ${step + 1} | ${list.size} | ${rate(list) { it.correct }} |")
        }
        appendLine()
        appendLine("## Неверные ответы")
        appendLine()
        appendLine("| № | Вина | Вопрос | Эталон | Ответ |")
        appendLine("|---|---|---|---|---|")
        for (j in judged.filter { it.correct != Verdict.PASS }) {
            val fault = when {
                j.row.status !in setOf("ANSWERED", "NO_INFO") -> "сбой"
                j.correct == Verdict.UNKNOWN -> "судья не знает"
                j.retrievalFault -> "поиск"
                else -> "генерация"
            }
            appendLine(
                "| ${j.row.id} | $fault | ${j.row.question} | ${j.row.reference.take(90)} | ${j.row.answer.take(140).replace("\n", " ")} |",
            )
        }
    }

    private companion object {
        /** С какой доли найденных слов эталона считается, что нужное в разделах было. */
        const val FOUND = 0.6
    }
}
