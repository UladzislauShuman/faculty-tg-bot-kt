package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCase
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCriterion
import shuman.bsu.famcs.rag.quality.judge.model.Verdict
import shuman.bsu.famcs.rag.quality.judge.service.AgreementCalculator
import shuman.bsu.famcs.rag.quality.judge.service.implementation.LlmAnswerJudge
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Сверка судьи корректности с ручной разметкой: `eval/judge-labels.yaml`.
 *
 * Берётся поле `human` (оценка автора). Для пробного прогона до разметки можно сверить с предварительной оценкой Claude:
 * `./gradlew evalTest -Peval.labels=provisional` — это проверка работоспособности промпта, а не калибровка.
 * Оценки `частично` и `не нашёл` считаются «неверно». Цель по калибровке: TPR и TNR не ниже 0,90.
 * Отчёт: `build/reports/eval/judge_calibration.md`. Нужен запущенный Ollama с моделью роли JUDGE.
 */
@Tag("eval")
class JudgeCalibrationEvalTest {
    /** Запись листа разметки. */
    private data class Label(
        val id: Int,
        val reference: String,
        val answer: String,
        val question: String,
        val positive: Boolean,
    )

    @Test
    fun `судья корректности согласуется с ручной разметкой`() = runBlocking {
        val field = System.getProperty("eval.labels", "human")
        val labels = readLabels(field)
        val judge = LlmAnswerJudge(EvalLlm.client(), PromptLibrary())

        val results = labels.map { label ->
            label to judge.judge(JudgeCriterion.CORRECTNESS, JudgeCase(label.question, label.answer, reference = label.reference))
        }
        val agreement = AgreementCalculator.compare(results.map { (label, result) -> label.positive to result.verdict })

        val text = buildString {
            appendLine("# Судья корректности против разметки `$field` (${labels.size} ответов)")
            appendLine()
            appendLine(
                "TP=${agreement.truePositive} TN=${agreement.trueNegative} FP=${agreement.falsePositive} FN=${agreement.falseNegative} не знаю=${agreement.unknown}",
            )
            appendLine("TPR=${"%.2f".format(agreement.tpr)}  TNR=${"%.2f".format(agreement.tnr)}  κ=${"%.2f".format(agreement.kappa)}")
            appendLine()
            appendLine("## Расхождения")
            appendLine()
            appendLine("| id | Разметка | Судья | Эталон | Ответ | Причина судьи |")
            appendLine("|---|---|---|---|---|---|")
            for ((label, result) in results.filter { (l, r) ->
                r.verdict == Verdict.UNKNOWN || (r.verdict == Verdict.PASS) != l.positive
            }) {
                appendLine(
                    "| ${label.id} | ${if (label.positive) "верно" else "неверно"} | ${result.verdict} | ${label.reference.take(80)} | " +
                        "${label.answer.take(120).replace("\n", " ")} | ${result.reason} |",
                )
            }
        }
        val report = Path.of("build/reports/eval/judge_calibration.md")
        Files.createDirectories(report.parent)
        Files.writeString(report, text)
        println(text.lines().take(5).joinToString("\n"))
        assertTrue(labels.isNotEmpty(), "в $field нет ни одной оценки: заполните eval/judge-labels.yaml")
    }

    /** Читает лист разметки; записи без оценки в выбранном поле пропускаются. */
    private fun readLabels(field: String): List<Label> {
        @Suppress("UNCHECKED_CAST")
        val items = Files.newBufferedReader(Path.of("eval/judge-labels.yaml")).use { Yaml().load<List<Map<String, Any?>>>(it) }
        return items.mapNotNull { item ->
            val mark = item[field]?.toString()?.trim()?.lowercase() ?: return@mapNotNull null
            Label(
                id = item.getValue("id") as Int,
                reference = item.getValue("reference").toString(),
                answer = item.getValue("answer").toString(),
                question = item.getValue("question").toString(),
                positive = mark == "верно",
            )
        }
    }
}
