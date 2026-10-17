package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки приватности (`bot.privacy`). */
@Validated
@ConfigurationProperties("bot.privacy")
data class PrivacyProperties(
    /** Секрет для хеширования Telegram ID в журнале ответов. Только из окружения; смена секрета разрывает связь со старыми записями. */
    @field:NotBlank val hashSecret: String,
    /** Сколько символов хеша хранить в журнале. 16 шестнадцатеричных знаков (64 бита) хватает, чтобы пользователи не пересекались. */
    @field:Min(8) @field:Max(64) val hashLength: Int,
) {
    init {
        // Значение секрета не попадает ни в сообщение об ошибке, ни в toString.
        require(hashSecret.length >= MIN_SECRET_LENGTH) {
            "bot.privacy.hash-secret короче $MIN_SECRET_LENGTH символов (переменная окружения BOT_HASH_SECRET)"
        }
    }

    override fun toString() = "PrivacyProperties(hashSecret=***, hashLength=$hashLength)"

    private companion object {
        const val MIN_SECRET_LENGTH = 16
    }
}
