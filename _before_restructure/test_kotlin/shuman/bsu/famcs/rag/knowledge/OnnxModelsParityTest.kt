package shuman.bsu.famcs.rag.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import shuman.bsu.famcs.rag.knowledge.embedding.OnnxEmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.onnx.OnnxSession
import shuman.bsu.famcs.rag.knowledge.rerank.OnnxCrossEncoderReranker
import shuman.bsu.famcs.rag.testsupport.Parity
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Паритет моделей: эмбеддинги bge-m3 и оценки реранкера в Kotlin (ONNX Runtime) совпадают с Python (PyTorch).
 * Нужны файлы моделей: `./scripts/prepare-models.sh`. Запуск: `./gradlew modelTest`.
 */
@Tag("model")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxModelsParityTest {
    private lateinit var embeddingSession: OnnxSession
    private lateinit var rerankerSession: OnnxSession
    private lateinit var embeddings: OnnxEmbeddingProvider
    private lateinit var reranker: OnnxCrossEncoderReranker

    @BeforeAll
    fun loadModels() {
        embeddingSession =
            OnnxSession(Path.of("models/bge-m3/model.onnx"), Path.of("models/bge-m3/tokenizer.json"), maxTokens = 512, intraOpThreads = 4)
        rerankerSession =
            OnnxSession(
                Path.of("models/reranker/model.onnx"),
                Path.of("models/reranker/tokenizer.json"),
                maxTokens = 512,
                intraOpThreads = 4,
            )
        embeddings =
            OnnxEmbeddingProvider(
                embeddingSession,
                dimension = 1024,
                batchSize = 4,
                outputName = "sentence_embedding",
                dispatcher = Dispatchers.Default,
            )
        reranker = OnnxCrossEncoderReranker(rerankerSession, batchSize = 8, outputName = "logits", dispatcher = Dispatchers.Default)
    }

    @AfterAll
    fun unloadModels() {
        embeddingSession.close()
        rerankerSession.close()
    }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    @Test
    fun `эмбеддинги bge-m3 совпадают с sentence-transformers`() = runBlocking<Unit> {
        val reference = Parity.json("dense_bge_m3.json")
        val texts = (0 until reference["texts"].size()).map { reference["texts"][it].asString() }

        val actual = embeddings.embedDocuments(texts)

        assertEquals(texts.size, actual.size)
        for ((index, vector) in actual.withIndex()) {
            val expected = FloatArray(1024) { reference["vectors"][index][it].doubleValue().toFloat() }
            assertEquals(1024, vector.size)
            val similarity = cosine(expected, vector)
            assertTrue(similarity > 0.9999, "косинус с эталоном для «${texts[index].take(40)}»: $similarity")
        }
    }

    @Test
    fun `запрос и документ кодируются одинаково`() = runBlocking<Unit> {
        val text = "Как подать документы на отработку?"
        assertTrue(cosine(embeddings.embedQuery(text), embeddings.embedDocuments(listOf(text)).single()) > 0.99999)
    }

    @Test
    fun `оценки реранкера совпадают с Python`() = runBlocking<Unit> {
        val pairs = Parity.json("reranker_scores.json")["pairs"]
        for (i in 0 until pairs.size()) {
            val pair = pairs[i]
            val score = reranker.score(pair["query"].asString(), listOf(pair["passage"].asString())).single()
            assertTrue(abs(score - pair["score"].doubleValue()) < 1e-3, "пара $i: ${pair["score"].doubleValue()} против $score")
        }
    }

    @Test
    fun `пакетная оценка не зависит от соседей по пакету`() = runBlocking<Unit> {
        val pairs = Parity.json("reranker_scores.json")["pairs"]
        val query = pairs[0]["query"].asString()
        val passages = (0 until pairs.size()).map { pairs[it]["passage"].asString() }

        val batch = reranker.score(query, passages)
        val single = passages.map { reranker.score(query, listOf(it)).single() }

        batch.indices.forEach { assertTrue(abs(batch[it] - single[it]) < 1e-4, "фрагмент $it: пакет ${batch[it]}, отдельно ${single[it]}") }
    }
}
