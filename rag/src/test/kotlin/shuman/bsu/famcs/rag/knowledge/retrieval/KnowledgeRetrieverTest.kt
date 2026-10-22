package shuman.bsu.famcs.rag.knowledge.retrieval

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.model.PageType
import shuman.bsu.famcs.rag.knowledge.model.SearchHit
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.RussianTextAnalyzer
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.testsupport.HashEmbeddingProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnowledgeRetrieverTest {
    private class FakeReader(private val hits: List<SearchHit>) : KnowledgeBaseReader {
        var lastRequest: HybridSearchRequest? = null

        override suspend fun search(request: HybridSearchRequest): List<SearchHit> {
            lastRequest = request
            return hits
        }

        override suspend fun chunkCount() = hits.size.toLong()
    }

    /** Реранкер, который считает релевантным фрагмент, содержащий слово «практик». */
    private val keywordReranker = object : Reranker {
        override suspend fun score(
            query: String,
            passages: List<String>,
        ) = passages.map { if ("практик" in it) 0.9 else 0.1 }
    }

    private fun hit(
        id: String,
        parent: String,
        parentText: String,
    ) = SearchHit(id, 0.5, "фрагмент $id", parent, parentText, "https://site/$parent", "Страница $parent", mapOf("H2" to "Раздел"), null)

    private fun retriever(
        hits: List<SearchHit>,
        reranker: Reranker?,
        topN: Int = 2,
        ranking: RankingPolicy = RankingPolicy.NEUTRAL,
        rerankWithContext: Boolean = false,
    ) = KnowledgeRetriever(
        embeddings = HashEmbeddingProvider(),
        sparse = Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
        reader = FakeReader(hits),
        reranker = reranker,
        topN = topN,
        meters = SimpleMeterRegistry(),
        ranking = ranking,
        rerankWithContext = rerankWithContext,
    )

    @Test
    fun `несколько фрагментов одного раздела дают один раздел`() = runTest {
        val hits = listOf(hit("1", "p1", "про экзамены"), hit("2", "p1", "про экзамены"), hit("3", "p2", "про практику"))

        val result = retriever(hits, reranker = null, topN = 5).retrieve("вопрос")

        assertEquals(listOf("p1", "p2"), result.passages.map { it.parentId })
        assertEquals(2, result.candidates)
    }

    @Test
    fun `реранкер переупорядочивает разделы по оценке и оставляет лучшие topN`() = runTest {
        val hits = listOf(hit("1", "p1", "про экзамены"), hit("2", "p2", "про практику"), hit("3", "p3", "про общежитие"))

        val result = retriever(hits, keywordReranker, topN = 2).retrieve("как пройти практику")

        assertEquals("p2", result.passages.first().parentId)
        assertEquals(2, result.passages.size)
        assertEquals(0.9, result.bestRelevance)
        assertEquals(3, result.candidates)
    }

    @Test
    fun `в результате все кандидаты в порядке реранкера с отметкой выбран и фрагментом, по которому нашлись`() = runTest {
        val hits = listOf(hit("1", "p1", "про экзамены"), hit("2", "p2", "про практику"), hit("3", "p3", "про общежитие"))

        val result = retriever(hits, keywordReranker, topN = 2).retrieve("как пройти практику")

        assertEquals(listOf("p2", "p1", "p3"), result.pool.map { it.parentId }, "порядок реранкера, не поиска")
        assertEquals(listOf(true, true, false), result.pool.map { it.selected }, "выбраны первые topN")
        assertEquals(0.9, result.pool.first().relevance)
        assertEquals("фрагмент 2", result.pool.first().matchedFragment)
        assertEquals("про практику".length, result.pool.first().parentChars)
    }

    @Test
    fun `без реранкера кандидаты идут в порядке поиска без оценок`() = runTest {
        val hits = listOf(hit("1", "p1", "а"), hit("2", "p2", "б"), hit("3", "p3", "в"))

        val result = retriever(hits, reranker = null, topN = 2).retrieve("вопрос")

        assertEquals(listOf("p1", "p2", "p3"), result.pool.map { it.parentId })
        assertEquals(listOf(true, true, false), result.pool.map { it.selected })
        assertNull(result.pool.first().relevance)
    }

    private fun typed(
        id: String,
        type: PageType,
        year: Int? = null,
    ) = hit(id, id, "про практику, страница $id").copy(pageType = type, year = year)

    @Test
    fun `перечень с высокой оценкой реранкера уступает основной странице с чуть меньшей`() = runTest {
        val hits = listOf(typed("list", PageType.LIST), typed("section", PageType.SECTION))
        val reranker = object : Reranker {
            override suspend fun score(
                query: String,
                passages: List<String>,
            ) = listOf(0.8, 0.5)
        }
        val policy = RankingPolicy(mapOf(PageType.LIST to 0.25), yearBoost = 1.5)

        val result = retriever(hits, reranker, topN = 1, ranking = policy).retrieve("вопрос")

        assertEquals("section", result.passages.single().parentId, "0,8 × 0,25 = 0,2 меньше 0,5")
        assertEquals(0.25, result.pool.first { it.parentId == "list" }.weight)
        assertEquals(0.8, result.pool.first { it.parentId == "list" }.relevance, "в трассе остаётся сама оценка реранкера")
    }

    @Test
    fun `год в вопросе поднимает хронику этого года`() = runTest {
        val hits = listOf(typed("h2001", PageType.HISTORY, 2001), typed("h1997", PageType.HISTORY, 1997))
        val reranker = object : Reranker {
            override suspend fun score(
                query: String,
                passages: List<String>,
            ) = listOf(0.6, 0.5)
        }
        val policy = RankingPolicy(mapOf(PageType.HISTORY to 0.7), yearBoost = 1.5)

        val result = retriever(hits, reranker, topN = 1, ranking = policy).retrieve("кто был деканом в 1997")

        assertEquals("h1997", result.passages.single().parentId)
    }

    @Test
    fun `реранкер получает название страницы и заголовок раздела перед текстом, если это включено`() = runTest {
        val seen = mutableListOf<String>()
        val reranker = object : Reranker {
            override suspend fun score(
                query: String,
                passages: List<String>,
            ): List<Double> {
                seen += passages
                return passages.map { 0.5 }
            }
        }
        val hits = listOf(hit("1", "p1", "про практику"))

        retriever(hits, reranker, rerankWithContext = true).retrieve("вопрос")
        retriever(hits, reranker, rerankWithContext = false).retrieve("вопрос")

        assertEquals("Страница p1. Раздел: Раздел\nпро практику", seen[0], "с контекстом")
        assertEquals("про практику", seen[1], "без контекста")
    }

    @Test
    fun `одинаковый текст раздела на разных страницах занимает одно место в выдаче`() = runTest {
        val hits = listOf(
            hit("1", "p1", "Приём документов до 17 июля"),
            hit("2", "p2", "Приём   документов до 17 июля "),
            hit("3", "p3", "про общежитие"),
        )

        val result = retriever(hits, reranker = null, topN = 5).retrieve("вопрос")

        assertEquals(listOf("p1", "p3"), result.passages.map { it.parentId }, "копия с другими пробелами отброшена")
    }

    @Test
    fun `без реранкера берутся первые topN в порядке поиска и оценки нет`() = runTest {
        val hits = listOf(hit("1", "p1", "а"), hit("2", "p2", "б"), hit("3", "p3", "в"))

        val result = retriever(hits, reranker = null, topN = 2).retrieve("вопрос")

        assertEquals(listOf("p1", "p2"), result.passages.map { it.parentId })
        assertNull(result.bestRelevance)
    }

    @Test
    fun `пустая база даёт пустой результат без вызова реранкера`() = runTest {
        val result = retriever(emptyList(), keywordReranker).retrieve("вопрос")

        assertEquals(0, result.passages.size)
        assertEquals(0, result.candidates)
    }

    @Test
    fun `в контекст уходит текст родителя, а в поиск — векторы запроса`() = runTest {
        val reader = FakeReader(listOf(hit("1", "p1", "Полный текст родительского раздела")))
        val retriever = KnowledgeRetriever(
            HashEmbeddingProvider(),
            Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
            reader,
            null,
            3,
            SimpleMeterRegistry(),
        )

        val passage = retriever.retrieve("документы на практику").passages.single()

        assertEquals("Полный текст родительского раздела", passage.text)
        assertEquals(4, reader.lastRequest?.dense?.size)
        assertEquals(
            2,
            reader.lastRequest
                ?.sparse
                ?.indices
                ?.size,
            "два содержательных слова запроса: «документы», «практику»",
        )
    }
}
