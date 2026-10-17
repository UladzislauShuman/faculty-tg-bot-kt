package shuman.bsu.famcs.rag.knowledge.model

/** Что нашлось по вопросу. */
data class RetrievalResult(
    val passages: List<RetrievedPassage>,
    /** Сколько разделов было до реранкера. Нужно для метрик и диагностики. */
    val candidates: Int,
) {
    /** Лучшая оценка среди найденного. По ней решается, есть ли в базе ответ. */
    val bestRelevance: Double? get() = passages.mapNotNull { it.relevance }.maxOrNull()
}
