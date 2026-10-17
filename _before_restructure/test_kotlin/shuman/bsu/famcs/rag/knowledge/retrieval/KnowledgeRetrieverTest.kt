package shuman.bsu.famcs.rag.knowledge.retrieval

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.RussianTextAnalyzer
import shuman.bsu.famcs.rag.knowledge.store.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.knowledge.store.SearchHit
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
    ) = KnowledgeRetriever(
        embeddings = HashEmbeddingProvider(),
        sparse = Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
        reader = FakeReader(hits),
        reranker = reranker,
        topN = topN,
        meters = SimpleMeterRegistry(),
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
