package shuman.bsu.famcs.rag.ui.http.dto

import shuman.bsu.famcs.rag.api.model.AnswerTrace

/**
 * Трасса ответа в теле HTTP-ответа (параметр `trace=true`): ветка, запрос после переформулирования, найденные разделы, модель, токены.
 *
 * @property route выбранная ветка.
 * @property query запрос после переформулирования.
 * @property passages разделы, которые увидела модель, в порядке реранкера.
 * @property candidates кандидаты поиска в порядке реранкера, с отметкой «выбран».
 * @property searchMs время поиска, мс.
 * @property rerankMs время реранкера, мс.
 * @property generationMs время генерации ответа, мс.
 * @property provider провайдер, давший ответ (`null`, если модель не вызывалась).
 * @property model модель, давшая ответ.
 * @property tokensIn токены на вход за весь вопрос.
 * @property tokensOut токены на выход за весь вопрос.
 */
data class TraceDto(
    val route: String,
    val query: String,
    val passages: List<PassageDto>,
    val candidates: List<CandidateDto>,
    val searchMs: Long,
    val rerankMs: Long,
    val generationMs: Long,
    val provider: String?,
    val model: String?,
    val tokensIn: Int,
    val tokensOut: Int,
) {
    /** Найденный раздел: адрес страницы, заголовок, оценка реранкера и текст, который ушёл в промпт. */
    data class PassageDto(
        val url: String,
        val title: String,
        val parentId: String,
        val relevance: Double?,
        val text: String,
    )

    /** Кандидат поиска: адрес, оценка реранкера, длина раздела, найденный фрагмент и признак «в промпте». */
    data class CandidateDto(
        val url: String,
        val title: String,
        val relevance: Double?,
        val chars: Int,
        val matchedFragment: String,
        val selected: Boolean,
        val pageType: String,
        val weight: Double,
    )

    companion object {
        /** Переводит трассу ядра в тело HTTP-ответа. */
        fun of(trace: AnswerTrace) = TraceDto(
            route = trace.route,
            query = trace.query,
            passages = trace.passages.map { PassageDto(it.url, it.title, it.parentId, it.relevance, it.text) },
            candidates = trace.candidates.map {
                CandidateDto(it.url, it.title, it.relevance, it.chars, it.matchedFragment, it.selected, it.pageType, it.weight)
            },
            searchMs = trace.searchMs,
            rerankMs = trace.rerankMs,
            generationMs = trace.generationMs,
            provider = trace.provider,
            model = trace.model,
            tokensIn = trace.tokensIn,
            tokensOut = trace.tokensOut,
        )
    }
}
