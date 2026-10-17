package shuman.bsu.famcs.rag.knowledge.sparse

import org.apache.lucene.util.StringHelper
import shuman.bsu.famcs.rag.knowledge.SparseVector
import kotlin.math.abs

/**
 * Превращает текст в разреженный вектор BM25 для поиска по ключевым словам в Qdrant.
 *
 * Как устроен BM25 в связке с Qdrant: документ хранит вес `tf·(k+1) / (tf + k·(1 − b + b·длина/средняя_длина))`
 * для каждого терма, запрос — единицу для каждого уникального терма, а множитель IDF (редкие слова важнее) Qdrant
 * применяет сам, если у разреженного вектора коллекции включён модификатор `idf`.
 * Идентификатор терма — `abs(murmur3_32(терм))`, формула совпадает с FastEmbed, чтобы индексы можно было сравнивать с Python.
 */
class Bm25SparseEncoder(
    private val analyzer: TextAnalyzer,
    /** Насыщение частоты терма: чем больше, тем дольше повторы слова повышают вес. Стандарт 1.2. */
    private val k: Double,
    /** Влияние длины документа: 0 — не влияет, 1 — полностью нормируется. Стандарт 0.75. */
    private val b: Double,
    /** Средняя длина документа в термах. У FastEmbed константа 256, а не среднее по корпусу. */
    private val averageLength: Double,
) {
    init {
        require(k > 0 && b in 0.0..1.0 && averageLength > 0) { "Неверные параметры BM25: k=$k, b=$b, avgLen=$averageLength" }
    }

    /** Вектор документа: вес зависит от числа вхождений терма и длины документа. */
    fun encodeDocument(text: String): SparseVector {
        val terms = analyzer.analyze(text)
        if (terms.isEmpty()) return EMPTY
        val counts = linkedMapOf<Int, Int>()
        terms.forEach { term -> counts.merge(termId(term), 1, Int::plus) }
        val lengthNorm = 1 - b + b * terms.size / averageLength
        return SparseVector(
            indices = counts.keys.toIntArray(),
            values = counts.values.map { tf -> (tf * (k + 1) / (tf + k * lengthNorm)).toFloat() }.toFloatArray(),
        )
    }

    /** Вектор запроса: каждый уникальный терм с весом 1. */
    fun encodeQuery(text: String): SparseVector {
        val ids = analyzer.analyze(text).map(::termId).distinct()
        if (ids.isEmpty()) return EMPTY
        return SparseVector(indices = ids.toIntArray(), values = FloatArray(ids.size) { 1.0f })
    }

    /** Идентификатор терма. Модуль считается в `Long`: у `Int.MIN_VALUE` нет положительного двойника в `Int`. */
    internal fun termId(term: String): Int {
        val bytes = term.toByteArray(Charsets.UTF_8)
        val hash = StringHelper.murmurhash3_x86_32(bytes, 0, bytes.size, SEED)
        return abs(hash.toLong()).toInt()
    }

    private companion object {
        /** Зерно хеша. У `mmh3.hash` в Python по умолчанию 0. */
        const val SEED = 0
        val EMPTY = SparseVector(IntArray(0), FloatArray(0))
    }
}
