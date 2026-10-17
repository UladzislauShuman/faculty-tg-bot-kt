package shuman.bsu.famcs.rag.common

import jakarta.validation.constraints.DecimalMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки оценки токенов (`bot.tokens`). */
@Validated
@ConfigurationProperties("bot.tokens")
data class TokenProperties(
    /** Символов на токен для оценки без токенизатора. Для русского текста 2.5–3.5, меньше — осторожнее. */
    @field:DecimalMin("0.5") val charsPerToken: Double,
)
