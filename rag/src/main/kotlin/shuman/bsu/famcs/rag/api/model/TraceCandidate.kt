package shuman.bsu.famcs.rag.api.model

/** Кандидат на попадание в промпт: раздел после поиска, до выбора реранкера. Входит в [AnswerTrace]. */
data class TraceCandidate(
    val url: String,
    val title: String,
    /** Оценка реранкера; `null`, если реранкер выключен. */
    val relevance: Double?,
    /** Длина раздела, символы. */
    val chars: Int,
    /** Начало мелкого фрагмента, по которому раздел нашёлся. */
    val matchedFragment: String,
    /** Раздел вошёл в итоговую выдачу (в промпт). */
    val selected: Boolean,
    /** Тип страницы (`SECTION`, `NEWS`, `HISTORY`, `LIST`…). */
    val pageType: String = "",
    /** Множитель порядка по типу страницы и году вопроса (1 — без поправки). */
    val weight: Double = 1.0,
)
