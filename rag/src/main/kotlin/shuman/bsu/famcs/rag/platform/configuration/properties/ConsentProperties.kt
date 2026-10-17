package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Согласие на обработку данных (`bot.consent`). Версия записывается вместе с согласием: смена версии требует нового согласия. */
@Validated
@ConfigurationProperties("bot.consent")
data class ConsentProperties(
    @field:NotBlank val version: String,
    /** Текст, который видит пользователь при `/start`. */
    @field:NotBlank val text: String,
    /** Подпись кнопки согласия. */
    @field:NotBlank val acceptButton: String,
)
