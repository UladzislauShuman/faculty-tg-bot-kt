package shuman.bsu.famcs.rag.knowledge.model

/**
 * Раздел-кандидат после гибридного поиска и склейки фрагментов в родителей: то, из чего реранкер выбирал итоговую выдачу.
 * Нужен для разбора поиска: видно, был ли нужный раздел среди кандидатов и куда его поставил реранкер.
 *
 * @property parentId идентификатор раздела.
 * @property url адрес страницы.
 * @property title заголовок страницы.
 * @property relevance оценка реранкера; `null`, если реранкер выключен.
 * @property parentChars длина раздела в символах (ровно столько уходит в промпт, если раздел выбран).
 * @property matchedFragment начало мелкого фрагмента, по которому раздел нашёлся.
 * @property selected раздел вошёл в итоговую выдачу.
 */
data class RetrievedCandidate(
    val parentId: String,
    val url: String,
    val title: String,
    val relevance: Double?,
    val parentChars: Int,
    val matchedFragment: String,
    val selected: Boolean,
)
