package shuman.bsu.famcs.rag.ui.http.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * Тело запроса `POST /api/v1/feedback`.
 *
 * @property userId идентификатор тестового пользователя (владелец ответа).
 * @property answerId номер ответа из ответа на вопрос.
 * @property rating оценка 1–5.
 * @property reason причина низкой оценки, необязательна.
 */
data class FeedbackRequest(
    val userId: Long,
    val answerId: Long,
    @field:Min(1) @field:Max(5) val rating: Int,
    val reason: String?,
)
