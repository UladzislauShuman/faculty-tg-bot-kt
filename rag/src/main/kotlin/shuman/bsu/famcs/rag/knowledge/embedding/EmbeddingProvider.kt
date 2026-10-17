package shuman.bsu.famcs.rag.knowledge.embedding

/**
 * Превращает текст в плотный вектор, в котором близкие по смыслу тексты лежат рядом.
 *
 * Модель эмбеддингов при индексации и при поиске должна быть одной и той же: смена модели означает полную переиндексацию.
 */
interface EmbeddingProvider {
    /** Размер вектора. Нужен при создании коллекции в Qdrant. */
    val dimension: Int

    /** Вектор поискового запроса. */
    suspend fun embedQuery(text: String): FloatArray

    /** Векторы документов, в том же порядке, что и тексты. */
    suspend fun embedDocuments(texts: List<String>): List<FloatArray>
}
