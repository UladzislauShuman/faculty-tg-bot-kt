package shuman.bsu.famcs.rag.api.model

import java.time.LocalDate

/** Источник, на который опирается ответ. Показывается строкой «Источник: …». */
data class AnswerSource(
    val url: String,
    val title: String?,
    /** Когда страница в последний раз проверялась индексатором. */
    val checkedAt: LocalDate?,
)
