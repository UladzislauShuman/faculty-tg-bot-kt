package shuman.bsu.famcs.rag.ui.telegram.configuration.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Настройки адаптера Telegram (`bot.telegram`).
 *
 * Адаптер выключается флагом `enabled`: так ядро можно запускать и проверять без токена (HTTP-вход, тесты).
 */
@Validated
@ConfigurationProperties("bot.telegram")
data class TelegramProperties(
    val enabled: Boolean,
    /** Токен бота от @BotFather. Только из окружения. Обязателен при `enabled: true`. */
    val token: String?,
    /** Telegram ID администраторов. Только они могут вызывать `/admin_*`. */
    val adminIds: Set<Long>,
    /** Сколько Telegram держит запрос длинного опроса открытым, пока нет новых сообщений. */
    val pollTimeout: Duration,
    /** Как часто обновлять индикатор «печатает…» (Telegram гасит его через 5 секунд). */
    val typingInterval: Duration,
    /**
     * Через сколько после получения вопроса показывать «печатает…». Мгновенные отказы (нет согласия, лимит, дубль) укладываются
     * в паузу и индикатора не вызывают: без неё спам заставлял бы бота слать запросы в Telegram ещё до предпроверок.
     */
    val typingStartDelay: Duration,
    /** Сколько обновлений обрабатывается одновременно до очереди ядра. Сверх лимита обновление отбрасывается. */
    @field:Min(1) val maxConcurrentUpdates: Int,
    /** Длина одного сообщения Telegram. Более длинный ответ режется на части по абзацам. */
    @field:Min(1) val messageMaxChars: Int,
    /** Сколько раз повторить отправку при ответе Telegram «слишком часто» (429). Пауза берётся из ответа Telegram. */
    @field:Min(0) val sendRetries: Int,
    /** Пауза после ошибки приёма обновлений. Без неё библиотека повторяет запрос мгновенно и засоряет журнал. */
    val errorPause: Duration,
    /** Шаблон подписи кнопки оценки. Метка `{n}` — число от 1 до 5. */
    @field:NotBlank val ratingLabel: String,
    @field:Valid val texts: Texts,
) {
    /** Токен в журнал и сообщения об ошибках не попадает. */
    override fun toString() = "TelegramProperties(enabled=$enabled, token=***, adminIds=${adminIds.size} шт.)"

    init {
        require(!enabled || !token.isNullOrBlank()) {
            "bot.telegram.token обязателен при bot.telegram.enabled=true (переменная окружения BOT_TELEGRAM_TOKEN)"
        }
    }

    /** Тексты команд и кнопок. Тексты отказов ядра лежат в `bot.messages`. */
    data class Texts(
        /** Приветствие после согласия и ответ на `/start` для тех, кто уже согласился. */
        @field:NotBlank val welcome: String,
        /** Справка `/help`. */
        @field:NotBlank val help: String,
        /** Ответ на `/new`. */
        @field:NotBlank val newSession: String,
        /** Вопрос перед удалением данных (`/forget`). */
        @field:NotBlank val forgetConfirm: String,
        @field:NotBlank val forgetButton: String,
        /** Данные удалены. */
        @field:NotBlank val forgotten: String,
        /** Согласие принято. Следом идёт приветствие. */
        @field:NotBlank val consentAccepted: String,
        /** Короткое всплывающее «спасибо» после оценки ответа. */
        @field:NotBlank val ratingThanks: String,
        /** Команда не распознана. */
        @field:NotBlank val unknownCommand: String,
        /** Команда `/admin_*` от человека, которого нет в списке администраторов. */
        @field:NotBlank val notAdmin: String,
    )
}
