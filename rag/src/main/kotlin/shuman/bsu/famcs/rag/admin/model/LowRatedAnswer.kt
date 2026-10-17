package shuman.bsu.famcs.rag.admin.model

/**
 * Ответ с низкой оценкой для разбора администратором.
 *
 * @property rating поставленная оценка.
 * @property reason причина, которую выбрал пользователь, если выбрал.
 * @property comment комментарий пользователя, если оставил.
 * @property query переформулированный запрос, по которому искали (исходный текст пользователя не хранится).
 * @property answer текст ответа бота.
 */
data class LowRatedAnswer(
    val rating: Int,
    val reason: String?,
    val comment: String?,
    val query: String?,
    val answer: String?,
)
