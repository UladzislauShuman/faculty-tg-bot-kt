package shuman.bsu.famcs.rag.ui.http.dto

import jakarta.validation.constraints.NotBlank

/**
 * Тело запроса `POST /api/v1/ask`.
 *
 * @property userId идентификатор тестового пользователя.
 * @property text текст вопроса.
 */
data class AskRequest(
    /** Идентификатор тестового пользователя. У каждого свой диалог, лимиты и согласие. */
    val userId: Long,
    @field:NotBlank val text: String,
)
