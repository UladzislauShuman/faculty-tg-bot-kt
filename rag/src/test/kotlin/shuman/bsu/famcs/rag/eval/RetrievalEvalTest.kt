package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import shuman.bsu.famcs.rag.eval.model.EvalCase
import shuman.bsu.famcs.rag.eval.model.RetrievalRow
import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Проверка поиска на наборе вопросов: попадает ли нужная страница в выдачу.
 *
 * Шаги: гибридный поиск → родители → реранкер, как у бота, но без языковых моделей, поэтому прогон быстрый и бесплатный.
 * Правильной считается страница из поля `url` набора (проверка по адресу, а не по идентификатору раздела: идентификатор
 * меняется при каждой правке страницы). В проверку входят только одиночные вопросы: шагам сценариев нужна переформулировка по истории.
 * Пороги пока не заданы: тест измеряет и пишет отчёт `build/reports/eval/retrieval.md`.
 * Запуск: `./gradlew evalTest` (нужны Qdrant с индексом и файлы моделей).
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalEvalTest {
    private lateinit var stack: EvalStack

    @BeforeAll
    fun start() {
        stack = EvalStack()
    }

    @AfterAll
    fun stop() = stack.close()

    @Test
    fun `поиск находит страницу, по которой написан вопрос`() = runBlocking {
        val cases = QaSetLoader.load(Path.of(System.getProperty("eval.qaSet", "eval/qa-set.yaml"))).filter { it.standalone }
        val rows = cases.map { search(it) }

        val report = Path.of("build/reports/eval/retrieval.md")
        Files.createDirectories(report.parent)
        Files.writeString(report, render(rows))
        println(summary(rows))
        assertTrue(rows.isNotEmpty(), "в наборе нет одиночных вопросов")
    }

    /** Прогоняет вопрос через поиск и запоминает, где в выдаче нужная страница. */
    private suspend fun search(case: EvalCase): RetrievalRow {
        val pool = stack.reader.search(
            HybridSearchRequest(dense = stack.embeddings.embedQuery(case.question), sparse = stack.sparse.encodeQuery(case.question)),
        )
        val result = stack.retriever.retrieve(case.question)
        val urls = result.passages.map { it.url }
        val index = urls.indexOf(case.expectedUrl)
        return RetrievalRow(
            case = case,
            poolHit = pool.any { it.url == case.expectedUrl },
            rank = if (index >= 0) index + 1 else null,
            urls = urls,
            scores = result.passages.map { it.relevance },
        )
    }

    /** Сводка: доля вопросов, у которых нужная страница в кандидатах, на первом месте и в итоговой тройке, плюс MRR. */
    private fun summary(rows: List<RetrievalRow>): String {
        val n = rows.size.toDouble()

        fun pct(count: Int) = "%.0f%%".format(100 * count / n)
        val mrr = rows.sumOf { row -> row.rank?.let { 1.0 / it } ?: 0.0 } / n
        return "Вопросов: ${rows.size}; кандидаты до реранкера: ${pct(rows.count { it.poolHit })}; " +
            "hit@1: ${pct(rows.count { it.rank == 1 })}; hit@3: ${pct(rows.count { it.rank != null })}; MRR: ${"%.3f".format(mrr)}"
    }

    /** Отчёт в Markdown: сводка, затем промахи с тем, что нашлось вместо нужной страницы. */
    private fun render(rows: List<RetrievalRow>): String = buildString {
        appendLine("# Поиск: страница из `url` набора в выдаче")
        appendLine()
        appendLine(summary(rows))
        appendLine()
        appendLine("## Промахи (нужной страницы нет в итоговой тройке)")
        appendLine()
        appendLine("| № | Вопрос | Нужная страница | Была в кандидатах | Нашлось (оценка реранкера) |")
        appendLine("|---|---|---|---|---|")
        for (row in rows.filter { it.rank == null }) {
            val found = row.urls
                .zip(
                    row.scores,
                ).joinToString("<br>") { (url, score) -> "${url.removePrefix(HOST)} (${score?.let { "%.2f".format(it) }})" }
            appendLine(
                "| ${row.case.id} | ${row.case.question} | ${row.case.expectedUrl.removePrefix(
                    HOST,
                )} | ${if (row.poolHit) "да" else "нет"} | $found |",
            )
        }
    }

    private companion object {
        const val HOST = "https://fpmi.bsu.by"
    }
}
