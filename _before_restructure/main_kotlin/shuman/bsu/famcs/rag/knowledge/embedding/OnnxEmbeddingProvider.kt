package shuman.bsu.famcs.rag.knowledge.embedding

import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import shuman.bsu.famcs.rag.knowledge.onnx.OnnxSession
import kotlin.math.sqrt

/**
 * Эмбеддинги BAAI/bge-m3, посчитанные внутри приложения на процессоре (ONNX Runtime).
 *
 * Берётся готовый выход модели `sentence_embedding`: слой Transformer, пулинг по первому токену (CLS) и нормализация
 * уже внутри ONNX-графа, как в sentence-transformers, который использовала Python-версия. Дополнительная L2-нормализация
 * здесь безопасна: у уже нормированного вектора она ничего не меняет.
 */
class OnnxEmbeddingProvider(
    private val onnx: OnnxSession,
    override val dimension: Int,
    /** Сколько текстов считается за один проход модели. Больше — быстрее на пачке, но больше памяти. */
    private val batchSize: Int,
    /** Название выхода модели с вектором предложения. */
    private val outputName: String,
    /** Диспетчер для вычислений на процессоре. Число потоков ограничено, чтобы хватало остальному приложению. */
    private val dispatcher: CoroutineDispatcher,
) : EmbeddingProvider {
    override suspend fun embedQuery(text: String): FloatArray = embedDocuments(listOf(text)).single()

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> = withContext(dispatcher) {
        texts.chunked(batchSize).flatMap(::embedBatch)
    }

    private fun embedBatch(batch: List<String>): List<FloatArray> {
        val encodings = onnx.tokenizer.batchEncode(batch)
        val inputIds = Array(encodings.size) { encodings[it].ids }
        val attentionMask = Array(encodings.size) { encodings[it].attentionMask }

        val tensors = buildMap<String, OnnxTensor> {
            put("input_ids", onnx.newTensor(inputIds))
            put("attention_mask", onnx.newTensor(attentionMask))
        }
        try {
            onnx.session.run(tensors).use { result ->
                val output = result.get(outputName).orElseThrow { IllegalStateException("У модели нет выхода '$outputName'") }

                @Suppress("UNCHECKED_CAST")
                val vectors = (output as OnnxTensor).value as Array<FloatArray>
                return vectors.map(::normalize)
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm == 0f) vector else FloatArray(vector.size) { vector[it] / norm }
    }
}
