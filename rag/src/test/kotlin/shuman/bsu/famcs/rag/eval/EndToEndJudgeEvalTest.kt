package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.eval.model.E2eRow
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.quality.judge.model.CorrectnessGrade
import shuman.bsu.famcs.rag.quality.judge.model.FactCheck
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCase
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCriterion
import shuman.bsu.famcs.rag.quality.judge.model.Verdict
import shuman.bsu.famcs.rag.quality.judge.service.ReferenceFacts
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
 * Корректность оценивается классами ([CorrectnessGrade]): «верно+» (эталон и больше) считается верным. Точные факты эталона
 * (телефоны, числа, ссылки, фамилии) дополнительно ищутся в ответе кодом ([ReferenceFacts]); если код и судья расходятся, ответ
 * попадает в список на ручной просмотр, вердикт не меняется.
 * Отчёт: `build/reports/eval/e2e_report.md`, оценки по вопросам: `e2e_judged.jsonl`. Нужен запущенный Ollama с моделью роли JUDGE.
 * Другие файлы: `-Peval.answers=путь` (ответы для оценки, по умолчанию `build/reports/eval/e2e_answers.jsonl`) и `-Peval.judgeDir=папка`
 * (куда писать отчёт, оценки и файл прогресса). Так судья переоценивает ответы прежнего прогона, не затирая новый.
 */
@Tag("eval")
class EndToEndJudgeEvalTest {
    private val mapper: JsonMapper = jacksonMapperBuilder().build()

    /** Строка ответа с вердиктами судьи и пометкой вины. */
    private data class Judged(
        val row: E2eRow,
        val grade: CorrectnessGrade,
        val facts: FactCheck,
        val faithful: Verdict?,
        val relevant: Verdict,
        val found: Double,
        val pageHit: Boolean,
    ) {
        /**
         * Итоговый вердикт корректности: ответ верен, если судья сказал «верно» или «верно+», либо все точные факты эталона найдены
         * кодом, а ответ не отказ. Правило введено 2026-10-22 по сверке с ручной разметкой прогона E: локальная модель-судья
         * называла «частично» верные подробные ответы (κ судьи по одним классам 0,45, с проверкой фактов 0,90). Правило подобрано
         * на тех же 223 ответах, что и разметка: цифру подтвердить на новых ответах.
         */
        val correct: Verdict
            get() = when {
                grade == CorrectnessGrade.UNKNOWN -> {
                    Verdict.UNKNOWN
                }

                grade.verdict == Verdict.PASS -> {
                    Verdict.PASS
                }

                row.status == "ANSWERED" && grade != CorrectnessGrade.REFUSAL && facts.facts.isNotEmpty() && facts.missing.isEmpty() -> {
                    Verdict.PASS
                }

                else -> {
                    Verdict.FAIL
                }
            }

        /** Код и судья расходятся: ответ смотрит человек. */
        val needsReview: Boolean get() = row.status in ANSWERED_STATUSES && facts.disagreesWith(grade)

        /** Ответ неверен, а нужное в разделах было: ошибка генерации. */
        val generationFault: Boolean get() = correct == Verdict.FAIL && found >= FOUND

        /** Ответ неверен, и нужного в найденных разделах нет: ошибка поиска (или данных). */
        val retrievalFault: Boolean get() = correct == Verdict.FAIL && found < FOUND
    }

    @Test
    fun `судья оценивает сквозной прогон`() = runBlocking {
        val rows = Files
            .readAllLines(Path.of(System.getProperty("eval.answers", "build/reports/eval/e2e_answers.jsonl")))
            .filter {
                it.isNotBlank()
            }.map { mapper.readValue<E2eRow>(it) }
        val judge = LlmAnswerJudge(EvalLlm.client(), PromptLibrary())
        // Ключевые факты берутся из набора при оценке, а не при сборе ответов: уточнённые факты действуют и на старые прогоны.
        val factsById = QaSetLoader.load(Path.of(System.getProperty("eval.qaSet", "eval/qa-set.yaml"))).associate { it.id to it.facts }

        // Оценки пишутся по одной в файл прогресса: при сбое (нехватка памяти, сон ноутбука) повторный запуск продолжает с того же места.
        val dir = Path.of(System.getProperty("eval.judgeDir", "build/reports/eval"))
        Files.createDirectories(dir)
        val progress = dir.resolve("e2e_judged_progress.jsonl")
        val done: Map<Int, Map<String, String?>> = if (Files.exists(progress)) {
            Files
                .readAllLines(progress)
                .filter {
                    it.isNotBlank()
                }.map { mapper.readValue<Map<String, String?>>(it) }
                .associateBy { it.getValue("id")!!.toInt() }
        } else {
            emptyMap()
        }
        val meter = EvalProgress("Судья", rows.size)
        var passed = 0
        var reviewed = 0
        val judged = rows.map { row ->
            val saved = done[row.id]
            val answered = row.status in ANSWERED_STATUSES
            val case = JudgeCase(row.question, row.answer, row.reference, row.passageTexts)
            val grade = saved?.let(::savedGrade)
                ?: if (answered) {
                    judge.judge(JudgeCriterion.CORRECTNESS, case).grade ?: CorrectnessGrade.UNKNOWN
                } else {
                    CorrectnessGrade.WRONG
                }
            val faithful = if (saved != null) {
                saved["faithful"]?.let(Verdict::valueOf)
            } else if (answered && row.passageTexts.isNotEmpty()) {
                judge.judge(JudgeCriterion.FAITHFULNESS, case).verdict
            } else {
                null
            }
            val relevant = saved?.get("relevant")?.let(Verdict::valueOf)
                ?: if (answered) judge.judge(JudgeCriterion.RELEVANCE, case).verdict else Verdict.FAIL
            if (saved == null) {
                val line = mapOf(
                    "id" to row.id.toString(),
                    "correct" to grade.verdict.name,
                    "grade" to grade.name,
                    "faithful" to faithful?.name,
                    "relevant" to relevant.name,
                )
                Files.writeString(
                    progress,
                    mapper.writeValueAsString(line) + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
            if (grade.verdict == Verdict.PASS) passed++
            reviewed++
            meter.step { "верно ${100 * passed / reviewed}%${if (saved != null) " (часть из файла прогресса)" else ""}" }
            Judged(
                row,
                grade,
                ReferenceFacts.check(ReferenceFacts.extract(row.reference, factsById[row.id].orEmpty()), row.answer),
                faithful,
                relevant,
                AnswerCoverage.share(row.reference, row.passageTexts.joinToString("\n")),
                row.expectedUrl in row.passageUrls,
            )
        }

        meter.finish("верно ${100 * passed / reviewed.coerceAtLeast(1)}%, на ручной просмотр ${judged.count { it.needsReview }}")
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
        "grade" to grade,
        "factsShare" to "%.2f".format(facts.share),
        "factsMissing" to facts.missing,
        "needsReview" to needsReview,
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
        appendLine("| Корректность итоговая (классы судьи + факты кодом, против эталона) | ${rate(judged) { it.correct }} |")
        appendLine("| Корректность только по классам судьи («верно» и «верно+») | ${rate(judged) { it.grade.verdict }} |")
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
        appendLine("| Расхождений кода и судьи (на ручной просмотр) | ${judged.count { it.needsReview }} |")
        appendLine()
        appendLine("## Классы корректности")
        appendLine()
        appendLine("| Класс | Ответов | Доля |")
        appendLine("|---|---|---|")
        CorrectnessGrade.entries.forEach { grade ->
            appendLine(
                "| ${GRADE_NAMES.getValue(grade)} | ${judged.count { it.grade == grade }} | ${pct(judged.count { it.grade == grade })} |",
            )
        }
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
        appendLine("## На ручной просмотр: код и судья расходятся")
        appendLine()
        appendLine("| № | Класс судьи | Найдено фактов | Нет в ответе | Вопрос | Эталон | Ответ |")
        appendLine("|---|---|---|---|---|---|---|")
        for (j in judged.filter { it.needsReview }) {
            appendLine(
                "| ${j.row.id} | ${GRADE_NAMES.getValue(j.grade)} | ${"%.0f%%".format(100 * j.facts.share)} | " +
                    "${j.facts.missing.joinToString(", ").take(60)} | ${j.row.question} | ${j.row.reference.take(90)} | " +
                    "${j.row.answer.take(140).replace("\n", " ")} |",
            )
        }
        appendLine()
        appendLine("## Неверные ответы")
        appendLine()
        appendLine("| № | Вина | Класс | Вопрос | Эталон | Ответ |")
        appendLine("|---|---|---|---|---|---|")
        for (j in judged.filter { it.correct != Verdict.PASS }) {
            val fault = when {
                j.row.status !in setOf("ANSWERED", "NO_INFO") -> "сбой"
                j.correct == Verdict.UNKNOWN -> "судья не знает"
                j.retrievalFault -> "поиск"
                else -> "генерация"
            }
            appendLine(
                "| ${j.row.id} | $fault | ${GRADE_NAMES.getValue(
                    j.grade,
                )} | ${j.row.question} | ${j.row.reference.take(90)} | ${j.row.answer.take(140).replace("\n", " ")} |",
            )
        }
    }

    /** Класс из файла прогресса; у строк прежнего формата (без класса) берётся по двоичному вердикту. */
    private fun savedGrade(saved: Map<String, String?>): CorrectnessGrade =
        saved["grade"]?.let(CorrectnessGrade::valueOf) ?: when (saved["correct"]?.let(Verdict::valueOf)) {
            Verdict.PASS -> CorrectnessGrade.CORRECT
            Verdict.FAIL -> CorrectnessGrade.WRONG
            else -> CorrectnessGrade.UNKNOWN
        }

    private companion object {
        /** С какой доли найденных слов эталона считается, что нужное в разделах было. */
        const val FOUND = 0.6

        /** Статусы, при которых бот ответил по существу (ответ или «не нашёл»). */
        val ANSWERED_STATUSES = setOf("ANSWERED", "NO_INFO")

        /** Названия классов в отчёте. */
        val GRADE_NAMES = mapOf(
            CorrectnessGrade.CORRECT to "верно",
            CorrectnessGrade.CORRECT_PLUS to "верно+",
            CorrectnessGrade.PARTIAL to "частично",
            CorrectnessGrade.WRONG to "неверно",
            CorrectnessGrade.REFUSAL to "отказ",
            CorrectnessGrade.UNKNOWN to "не знаю",
        )
    }
}
