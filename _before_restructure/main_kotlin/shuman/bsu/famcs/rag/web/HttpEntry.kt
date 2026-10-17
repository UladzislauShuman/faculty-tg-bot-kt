package shuman.bsu.famcs.rag.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.ChatId
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UpdateId
import shuman.bsu.famcs.rag.core.AnswerSource
import shuman.bsu.famcs.rag.core.BotCore
import shuman.bsu.famcs.rag.core.CoreReply
import shuman.bsu.famcs.rag.core.CoreRequest
import shuman.bsu.famcs.rag.dialog.ConsentProperties
import shuman.bsu.famcs.rag.dialog.UserDirectory
import shuman.bsu.famcs.rag.feedback.FeedbackService
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

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

data class AskRequest(
    /** Идентификатор тестового пользователя. У каждого свой диалог, лимиты и согласие. */
    val userId: Long,
    @field:NotBlank val text: String,
)

data class AskResponse(
    val status: String,
    val text: String,
    val sources: List<SourceDto>,
    val answerId: Long?,
    val elapsedMs: Long,
) {
    data class SourceDto(
        val url: String,
        val title: String?,
        val checkedAt: String?,
    )
}

data class FeedbackRequest(
    val userId: Long,
    val answerId: Long,
    @field:Min(1) @field:Max(5) val rating: Int,
    val reason: String?,
)

/**
 * HTTP-вход для проверок: вопрос и оценка без Telegram.
 * Вызывает то же ядро, что и бот, поэтому проверяется весь путь: предпроверки, лимиты, поиск, модели, запись.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "bot.http", name = ["enabled"], havingValue = "true")
class HttpEntryController(
    private val core: BotCore,
    private val users: UserDirectory,
    private val feedback: FeedbackService,
    private val consent: ConsentProperties,
    private val properties: HttpEntryProperties,
    private val clock: Clock,
) {
    /** Номера обновлений для HTTP-вызовов: от текущего времени, чтобы не пересекаться с номерами Telegram и предыдущих запусков. */
    private val updateIds = AtomicLong(clock.millis())

    @PostMapping("/ask")
    fun ask(
        @RequestHeader("X-Api-Key") apiKey: String?,
        @Valid @RequestBody request: AskRequest,
    ): AskResponse {
        authorize(apiKey)
        val userId = TelegramId(request.userId)
        // Блокирующий вызов допустим: это служебный вход, а не путь реальных пользователей.
        val reply = runBlocking {
            if (properties.autoConsent) {
                users.visit(userId, clock.instant())
                users.grantConsent(userId, consent.version, clock.instant())
            }
            core.ask(CoreRequest(userId, ChatId(request.userId), UpdateId(updateIds.incrementAndGet()), request.text, clock.instant()))
        }
        return reply.toResponse()
    }

    @PostMapping("/feedback")
    fun rate(
        @RequestHeader("X-Api-Key") apiKey: String?,
        @Valid @RequestBody request: FeedbackRequest,
    ): Map<String, Boolean> {
        authorize(apiKey)
        if (request.rating !in 1..5) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "rating должен быть от 1 до 5")
        val saved = runBlocking { feedback.rate(AnswerId(request.answerId), TelegramId(request.userId), request.rating, request.reason) }
        return mapOf("saved" to saved)
    }

    /** Сравнение ключей за постоянное время: по времени ответа ключ не подобрать. */
    private fun authorize(apiKey: String?) {
        val expected = requireNotNull(properties.apiKey).toByteArray()
        val actual = apiKey.orEmpty().toByteArray()
        if (!MessageDigest.isEqual(expected, actual)) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный ключ")
    }

    private fun CoreReply.toResponse() = AskResponse(
        status = status.name,
        text = text,
        sources = sources.map(AnswerSource::toDto),
        answerId = answerId?.value,
        elapsedMs = elapsed.inWholeMilliseconds,
    )
}

private fun AnswerSource.toDto() = AskResponse.SourceDto(url = url, title = title, checkedAt = checkedAt?.toString())
