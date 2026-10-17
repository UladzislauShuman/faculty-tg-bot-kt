package shuman.bsu.famcs.rag.knowledge.model

/** Разреженный вектор: индексы терминов и их веса. */
data class SparseVector(
    val indices: IntArray,
    val values: FloatArray,
) {
    init {
        require(indices.size == values.size) { "Индексов (${indices.size}) и весов (${values.size}) должно быть поровну" }
    }

    override fun equals(other: Any?): Boolean =
        other is SparseVector && indices.contentEquals(other.indices) && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * indices.contentHashCode() + values.contentHashCode()
}
