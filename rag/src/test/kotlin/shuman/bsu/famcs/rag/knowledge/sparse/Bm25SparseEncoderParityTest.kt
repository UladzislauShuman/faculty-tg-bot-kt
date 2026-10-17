package shuman.bsu.famcs.rag.knowledge.sparse

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.model.SparseVector
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.FastEmbedCompatibleAnalyzer
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.RussianTextAnalyzer
import shuman.bsu.famcs.rag.testsupport.Parity
import tools.jackson.databind.JsonNode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Паритет sparse-кодировщика с `FastEmbedSparse("Qdrant/bm25")` Python-версии: те же индексы термов и те же веса. */
class Bm25SparseEncoderParityTest {
    private val englishStopWords = Bm25SparseEncoder::class.java
        .getResource("/knowledge/stopwords-english.txt")!!
        .readText()
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    private val encoder = Bm25SparseEncoder(
        analyzer = FastEmbedCompatibleAnalyzer(stopWords = englishStopWords, maxTokenLength = 40),
        k = 1.2,
        b = 0.75,
        averageLength = 256.0,
    )

    private fun JsonNode.toSparse(): SparseVector {
        val pairs = (0 until this["indices"].size()).map { i -> this["indices"][i].intValue() to this["values"][i].doubleValue().toFloat() }
        val sorted = pairs.sortedBy { it.first }
        return SparseVector(sorted.map { it.first }.toIntArray(), sorted.map { it.second }.toFloatArray())
    }

    private fun SparseVector.sortedByIndex(): SparseVector {
        val order = indices.indices.sortedBy { indices[it] }
        return SparseVector(order.map { indices[it] }.toIntArray(), order.map { values[it] }.toFloatArray())
    }

    private fun assertSameVector(
        expected: SparseVector,
        actual: SparseVector,
        label: String,
    ) {
        assertEquals(expected.indices.toList(), actual.indices.toList(), "индексы термов: $label")
        expected.values.indices.forEach { i ->
            assertTrue(
                abs(expected.values[i] - actual.values[i]) < 1e-5f,
                "вес терма ${expected.indices[i]} в «$label»: ${expected.values[i]} против ${actual.values[i]}",
            )
        }
    }

    @Test
    fun `векторы документов совпадают с FastEmbed`() {
        for (doc in Parity.json("sparse_bm25.json")["documents"]) {
            assertSameVector(doc.toSparse(), encoder.encodeDocument(doc["text"].asString()).sortedByIndex(), doc["text"].asString())
        }
    }

    @Test
    fun `векторы запросов совпадают с FastEmbed`() {
        for (query in Parity.json("sparse_bm25.json")["queries"]) {
            assertSameVector(query.toSparse(), encoder.encodeQuery(query["text"].asString()).sortedByIndex(), query["text"].asString())
        }
    }

    @Test
    fun `русский анализатор сводит словоформы к одному терму`() {
        val russian = Bm25SparseEncoder(RussianTextAnalyzer(maxTokenLength = 40), k = 1.2, b = 0.75, averageLength = 256.0)
        assertEquals(russian.encodeQuery("документы").indices.toList(), russian.encodeQuery("документов").indices.toList())
        assertNotEquals(encoder.encodeQuery("документы").indices.toList(), encoder.encodeQuery("документов").indices.toList())
    }

    @Test
    fun `пустой текст даёт пустой вектор`() {
        assertEquals(0, encoder.encodeDocument("   ").indices.size)
        assertEquals(
            0,
            encoder
                .encodeQuery("и а в")
                .indices.size
                .coerceAtMost(0),
        )
    }
}
