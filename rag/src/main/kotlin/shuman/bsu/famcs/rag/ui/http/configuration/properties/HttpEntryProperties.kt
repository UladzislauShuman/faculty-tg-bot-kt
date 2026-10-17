package shuman.bsu.famcs.rag.ui.http.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Настройки HTTP-входа для проверок (`bot.http`).
 *
 * Вход нужен автору: прогон тестовых вопросов без Telegram (тот же контракт ядра, что и у бота).
 * По умолчанию выключен. Включённый, он закрыт ключом и должен слушать только локальный адрес (см. docker-compose).
 */
@Validated
@ConfigurationProperties("bot.http")
data class HttpEntryProperties(
    val enabled: Boolean,
    /** Ключ доступа в заголовке `X-Api-Key`. Только из окружения. Обязателен при `enabled: true`. */
    val apiKey: String?,
    /** Записывать ли согласие для тестовых пользователей автоматически. Иначе первый вопрос будет отклонён как «нет согласия». */
    val autoConsent: Boolean,
) {
    init {
        require(!enabled || (apiKey?.length ?: 0) >= MIN_KEY_LENGTH) {
            "bot.http.api-key должен быть не короче $MIN_KEY_LENGTH символов при bot.http.enabled=true (переменная BOT_HTTP_API_KEY)"
        }
    }

    /** Ключ доступа в журнал и сообщения об ошибках не попадает. */
    override fun toString() = "HttpEntryProperties(enabled=$enabled, apiKey=***, autoConsent=$autoConsent)"

    private companion object {
        const val MIN_KEY_LENGTH = 16
    }
}
