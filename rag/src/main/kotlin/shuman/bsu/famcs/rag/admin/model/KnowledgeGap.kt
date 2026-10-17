package shuman.bsu.famcs.rag.admin.model

/**
 * Запрос, на который в базе знаний не нашлось ответа: подсказка, какие материалы стоит добавить.
 *
 * @property query переформулированный запрос.
 * @property count сколько раз его задавали за период.
 */
data class KnowledgeGap(
    val query: String,
    val count: Int,
)
