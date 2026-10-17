package shuman.bsu.famcs.rag.common

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

/**
 * Псевдоним пользователя для журнала ответов и метрик.
 *
 * Зачем: для разбора качества нужно понимать, что два ответа даны одному человеку, но Telegram ID в журнале хранить не нужно.
 * HMAC-SHA256 с секретом необратим без секрета; без секрета нельзя перебрать числовые ID и узнать, кому принадлежит запись.
 */
class UserHasher(
    secret: String,
    /** Сколько символов хеша хранить. 16 шестнадцатеричных (64 бита) достаточно, чтобы пользователи не пересекались. */
    private val length: Int,
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)

    init {
        require(length in 8..64) { "Длина хеша должна быть от 8 до 64 символов" }
    }

    fun hash(userId: TelegramId): UserHash {
        // Mac не потокобезопасен, поэтому на каждый вызов создаётся свой экземпляр: это дёшево.
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        val digest = mac.doFinal(userId.value.toString().toByteArray(Charsets.UTF_8))
        return UserHash(HexFormat.of().formatHex(digest).take(length))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
