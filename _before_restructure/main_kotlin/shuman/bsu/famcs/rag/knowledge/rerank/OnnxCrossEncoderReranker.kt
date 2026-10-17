package shuman.bsu.famcs.rag.knowledge.rerank

import ai.djl.util.PairList
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import shuman.bsu.famcs.rag.knowledge.onnx.OnnxSession
import kotlin.math.exp

/**
 * Реранкер на кросс-энкодере (DiTy/cross-encoder-russian-msmarco, BERT), посчитанном на процессоре через ONNX Runtime.
 *
 * Пара «вопрос, фрагмент» подаётся в модель одной последовательностью, на выходе один логит; сигмоида превращает его
 * в оценку от 0 до 1, как это делает sentence-transformers в Python-версии. Совпадение оценок проверяется тестом паритета.
 */
class OnnxCrossEncoderReranker(
    private val onnx: OnnxSession,
    /** Сколько пар считается за один проход модели. */
    private val batchSize: Int,
    /** Название выхода модели с логитом. */
    private val outputName: String,
    private val dispatcher: CoroutineDispatcher,
) : Reranker {
    override suspend fun score(
        query: String,
        passages: List<String>,
    ): List<Double> = withContext(dispatcher) {
        passages.chunked(batchSize).flatMap { batch -> scoreBatch(query, batch) }
    }

    private fun scoreBatch(
        query: String,
        batch: List<String>,
    ): List<Double> {
        val pairs = PairList<String, String>().apply { batch.forEach { passage -> add(query, passage) } }
        val encodings = onnx.tokenizer.batchEncode(pairs)

        val tensors = buildMap<String, OnnxTensor> {
            put("input_ids", onnx.newTensor(Array(encodings.size) { encodings[it].ids }))
            put("attention_mask", onnx.newTensor(Array(encodings.size) { encodings[it].attentionMask }))
            // BERT различает части пары по типам токенов. У моделей без такого входа его не передаём.
            if ("token_type_ids" in onnx.inputNames) {
                put("token_type_ids", onnx.newTensor(Array(encodings.size) { encodings[it].typeIds }))
            }
        }
        try {
            onnx.session.run(tensors).use { result ->
                val output = result.get(outputName).orElseThrow { IllegalStateException("У модели нет выхода '$outputName'") }

                @Suppress("UNCHECKED_CAST")
                val logits = (output as OnnxTensor).value as Array<FloatArray>
                return logits.map { sigmoid(it[0].toDouble()) }
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
