package shuman.bsu.famcs.rag.knowledge.model

/** Что нашлось по вопросу. */
data class RetrievalResult(
    val passages: List<RetrievedPassage>,
    /** Сколько разделов было до реранкера. Нужно для метрик и диагностики. */
    val candidates: Int,
    /** Все кандидаты до реранкера в порядке поиска с их оценками и отметкой «выбран». */
    val pool: List<RetrievedCandidate> = emptyList(),
    /** Время гибридного поиска (векторы запроса и Qdrant), мс. */
    val searchMillis: Long = 0,
    /** Время реранкера, мс; 0, если реранкер выключен. */
    val rerankMillis: Long = 0,
) {
    /** Лучшая оценка среди найденного. По ней решается, есть ли в базе ответ. */
    val bestRelevance: Double? get() = passages.mapNotNull { it.relevance }.maxOrNull()
}
