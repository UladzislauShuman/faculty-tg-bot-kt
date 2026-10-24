package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import shuman.bsu.famcs.rag.eval.model.EvalCase
import shuman.bsu.famcs.rag.knowledge.model.PageType
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.retrieval.RankingPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Сравнение вариантов порядка выдачи на одном индексе: что даёт контекст реранкеру, поправка по типу страницы, год вопроса, число разделов.
 *
 * Индекс один (его строит бот), меняются только настройки поиска, поэтому прогон не требует переиндексации и языковых моделей.
 * Меры: страница из `url` набора в первых разделах (занижено, см. TECH_DEBT №1), доля слов эталона в найденных разделах
 * (не зависит от разметки страниц), доля вопросов с покрытием не меньше 60%, доля основных страниц и перечней в выдаче, размер промпта.
 * Отчёт: `build/reports/eval/ranking_variants.md`. Запуск: `./gradlew evalTest`.
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RankingVariantsEvalTest {
    private lateinit var stack: EvalStack

    /** Вариант настроек для сравнения. */
    private data class Variant(
        val name: String,
        val retriever: () -> KnowledgeRetriever,
    )

    /** Итоги варианта по набору вопросов. */
    private data class Score(
        val name: String,
        val hit1: Double,
        val hit3: Double,
        val mrr: Double,
        val coverage: Double,
        val covered: Double,
        val sectionShare: Double,
        val listShare: Double,
        val promptChars: Double,
    )

    @BeforeAll
    fun start() {
        stack = EvalStack()
    }

    @AfterAll
    fun stop() = stack.close()

    @Test
    fun `варианты порядка выдачи на одном индексе`() = runBlocking {
        val cases = QaSetLoader.load(Path.of(System.getProperty("eval.qaSet", "eval/qa-set.yaml"))).filter { it.standalone }
        val properties = stack.properties.ranking

        // Контекст реранкеру проверен на прогонах C–F и вредит (hit@1 ниже): варианты с ним убраны, реранкер без контекста, как в приложении.
        fun policy(orphanWeight: Double) = RankingPolicy(properties.typeWeights, properties.yearBoost, orphanWeight)
        val variants = listOf(
            Variant("как было (без поправок)") { stack.retriever(RankingPolicy.NEUTRAL, rerankWithContext = false) },
            Variant("вес типа и год, сироты без понижения (1,0)") { stack.retriever(policy(1.0), rerankWithContext = false) },
            Variant("вес типа и год, сироты 0,8") { stack.retriever(policy(0.8), rerankWithContext = false) },
            Variant("вес типа и год, сироты 0,6") { stack.retriever(policy(0.6), rerankWithContext = false) },
            Variant("вес типа и год, сироты 0,4") { stack.retriever(policy(0.4), rerankWithContext = false) },
            Variant("настройки приложения (сироты ${properties.orphanWeight}), 5 разделов") {
                stack.retriever(policy(properties.orphanWeight), rerankWithContext = false, topN = 5)
            },
        )
        val scores = variants.mapIndexed { index, variant ->
            println("Варианты порядка: ${index + 1} из ${variants.size} — ${variant.name}")
            score(variant.name, variant.retriever(), cases)
        }

        val report = Path.of("build/reports/eval/ranking_variants.md")
        Files.createDirectories(report.parent)
        val text = render(scores, cases.size)
        Files.writeString(report, text)
        println(text)
        assertTrue(scores.isNotEmpty())
    }

    /** Прогоняет все вопросы через поиск варианта и считает показатели. */
    private suspend fun score(
        name: String,
        retriever: KnowledgeRetriever,
        cases: List<EvalCase>,
    ): Score {
        var hit1 = 0
        var hit3 = 0
        var mrr = 0.0
        var coverage = 0.0
        var covered = 0
        var sections = 0
        var lists = 0
        var passages = 0
        var chars = 0.0
        val progress = EvalProgress("Вариант «$name»", cases.size, every = 20)
        for (case in cases) {
            progress.step()
            val found = retriever.retrieve(case.question).passages
            val rank = found.indexOfFirst { it.url == case.expectedUrl } + 1
            if (rank == 1) hit1++
            if (rank in 1..3) hit3++
            if (rank > 0) mrr += 1.0 / rank
            val share = AnswerCoverage.share(case.referenceAnswer, found.joinToString("\\n") { it.text })
            coverage += share
            if (share >= COVERED) covered++
            passages += found.size
            sections +=
                found.count { it.pageType == PageType.SECTION || it.pageType == PageType.DEPARTMENT || it.pageType == PageType.CATALOG }
            lists += found.count { it.pageType == PageType.LIST }
            chars += found.sumOf { it.text.length }
        }
        val n = cases.size.toDouble()
        return Score(
            name,
            hit1 / n,
            hit3 / n,
            mrr / n,
            coverage / n,
            covered / n,
            sections.toDouble() / passages,
            lists.toDouble() / passages,
            chars / n,
        )
    }

    /** Таблица вариантов. */
    private fun render(
        scores: List<Score>,
        n: Int,
    ): String = buildString {
        fun pct(x: Double) = "%.0f%%".format(100 * x)
        appendLine("# Порядок выдачи: варианты на одном индексе ($n одиночных вопросов)")
        appendLine()
        appendLine(
            "| Вариант | hit@1 | hit@3 | MRR | слова эталона в разделах | вопросов с ≥60% слов | основных страниц в выдаче | перечней в выдаче | знаков в промпте |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|")
        for (s in scores) {
            appendLine(
                "| ${s.name} | ${pct(s.hit1)} | ${pct(s.hit3)} | ${"%.3f".format(s.mrr)} | ${pct(s.coverage)} | ${pct(s.covered)} | " +
                    "${pct(s.sectionShare)} | ${pct(s.listShare)} | ${"%.0f".format(s.promptChars)} |",
            )
        }
    }

    private companion object {
        /** С какой доли найденных слов эталона вопрос считается покрытым. */
        const val COVERED = 0.6
    }
}
