package shuman.bsu.famcs.rag.platform.privacy

import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.identifier.UserHash
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    /**
     * Считает псевдоним пользователя: HMAC-SHA256 от Telegram ID с секретной солью, обрезанный до заданной длины.
     *
     * @param userId Telegram ID.
     * @return псевдоним; одному пользователю всегда соответствует один и тот же.
     */
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
