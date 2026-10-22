package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import shuman.bsu.famcs.rag.eval.model.EvalCase
import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.model.SearchHit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Сравнение размеров родительских разделов и работы реранкера на одном наборе вопросов.
 *
 * Фрагменты ищутся один раз, дальше для каждого варианта родители пересобираются ([ParentRegrouper]) и реранкер оценивает их заново.
 * Меряются: страница из `url` набора в первых трёх (занижено, см. TECH_DEBT №1), доля вопросов, у которых слова эталонного ответа
 * найдены в трёх разделах (не зависит от разметки страниц, но грубая), и место нужной страницы до и после реранкера.
 * Отчёт: `build/reports/eval/chunking_variants.md`. Запуск: `./gradlew evalTest`.
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChunkingVariantsEvalTest {
    private lateinit var stack: EvalStack

    /** Вариант настроек для сравнения: имя, предельный размер родителя (`null` — как в индексе) и фильтр страниц. */
    private data class Variant(
        val name: String,
        val maxParentChars: Int? = null,
        val withoutHistory: Boolean = false,
    )

    /** Итог варианта по набору вопросов. */
    private data class Score(
        val variant: String,
        val hit1: Double,
        val hit3: Double,
        val mrr: Double,
        val coverage: Double,
        val coverageShare: Double,
        val hit3BeforeRerank: Double,
        val meanPromptChars: Double,
    )

    @BeforeAll
    fun start() {
        stack = EvalStack()
    }

    @AfterAll
    fun stop() = stack.close()

    @Test
    fun `размер родителя и реранкер`() = runBlocking {
        val cases = QaSetLoader.load(Path.of(System.getProperty("eval.qaSet", "eval/qa-set.yaml"))).filter { it.standalone }
        val pools = cases.map { case ->
            stack.reader.search(
                HybridSearchRequest(dense = stack.embeddings.embedQuery(case.question), sparse = stack.sparse.encodeQuery(case.question)),
            )
        }
        val variants = listOf(
            Variant("как в индексе"),
            Variant("родитель до 3000", maxParentChars = 3000),
            Variant("родитель до 1500", maxParentChars = 1500),
            Variant("без страниц history-*", withoutHistory = true),
            Variant("до 1500 и без history-*", maxParentChars = 1500, withoutHistory = true),
        )
        val scores = variants.map { score(it, cases, pools) }

        val report = Path.of("build/reports/eval/chunking_variants.md")
        Files.createDirectories(report.parent)
        Files.writeString(report, render(scores, cases.size))
        println(render(scores, cases.size))
        assertTrue(scores.isNotEmpty())
    }

    /** Прогоняет все вопросы через вариант: пересборка родителей, фильтр, реранкер, подсчёт показателей. */
    private suspend fun score(
        variant: Variant,
        cases: List<EvalCase>,
        pools: List<List<SearchHit>>,
    ): Score {
        var hit1 = 0
        var hit3 = 0
        var hit3Before = 0
        var mrr = 0.0
        var coverage = 0.0
        var covered = 0
        var promptChars = 0.0
        cases.forEachIndexed { i, case ->
            var hits = pools[i]
            if (variant.withoutHistory) hits = hits.filterNot { HISTORY.containsMatchIn(it.url) }
            variant.maxParentChars?.let { hits = ParentRegrouper.regroup(hits, it) }
            val parents = hits.distinctBy { it.parentId }
            val ranked = if (parents.isEmpty()) {
                emptyList()
            } else {
                val scores = requireNotNull(stack.reranker).score(case.question, parents.map { it.parentText })
                parents.zip(scores).sortedByDescending { it.second }.map { it.first }
            }
            val top = ranked.take(stack.topN)
            val rank = top.indexOfFirst { it.url == case.expectedUrl } + 1
            if (rank == 1) hit1++
            if (rank > 0) {
                hit3++
                mrr += 1.0 / rank
            }
            if (parents.take(stack.topN).any { it.url == case.expectedUrl }) hit3Before++
            val share = AnswerCoverage.share(case.referenceAnswer, top.joinToString("\n") { it.parentText })
            coverage += share
            if (share >= COVERED) covered++
            promptChars += top.sumOf { it.parentText.length }
        }
        val n = cases.size.toDouble()
        return Score(variant.name, hit1 / n, hit3 / n, mrr / n, coverage / n, covered / n, hit3Before / n, promptChars / n)
    }

    /** Таблица вариантов для отчёта. */
    private fun render(
        scores: List<Score>,
        n: Int,
    ): String = buildString {
        fun pct(x: Double) = "%.0f%%".format(100 * x)
        appendLine("# Размер родителя и реранкер ($n одиночных вопросов)")
        appendLine()
        appendLine(
            "| Вариант | hit@1 | hit@3 | MRR | hit@3 до реранкера | слова ответа в разделах (среднее) | вопросов с ≥60% слов | символов в промпте (среднее) |",
        )
        appendLine("|---|---|---|---|---|---|---|---|")
        for (s in scores) {
            appendLine(
                "| ${s.variant} | ${pct(s.hit1)} | ${pct(s.hit3)} | ${"%.3f".format(s.mrr)} | ${pct(s.hit3BeforeRerank)} | " +
                    "${pct(s.coverage)} | ${pct(s.coverageShare)} | ${"%.0f".format(s.meanPromptChars)} |",
            )
        }
    }

    private companion object {
        val HISTORY = Regex("/history-\\d{4}")

        /** С какой доли найденных слов эталона вопрос считается покрытым. */
        const val COVERED = 0.6
    }
}
