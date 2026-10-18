package shuman.bsu.famcs.rag.ui.http.controller

import jakarta.validation.Valid
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import shuman.bsu.famcs.rag.api.FeedbackService
import shuman.bsu.famcs.rag.api.RagService
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.api.model.AnswerSource
import shuman.bsu.famcs.rag.api.model.RagReply
import shuman.bsu.famcs.rag.api.model.RagRequest
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.identifier.UpdateId
import shuman.bsu.famcs.rag.ui.http.configuration.properties.HttpEntryProperties
import shuman.bsu.famcs.rag.ui.http.dto.AskRequest
import shuman.bsu.famcs.rag.ui.http.dto.AskResponse
import shuman.bsu.famcs.rag.ui.http.dto.FeedbackRequest
import shuman.bsu.famcs.rag.ui.http.dto.TraceDto
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP-вход для проверок: вопрос и оценка без Telegram.
 * Вызывает то же ядро, что и бот, поэтому проверяется весь путь: предпроверки, лимиты, поиск, модели, запись.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "bot.http", name = ["enabled"], havingValue = "true")
class HttpEntryController(
    private val core: RagService,
    private val users: UserService,
    private val feedback: FeedbackService,
    private val consent: ConsentProperties,
    private val properties: HttpEntryProperties,
    private val clock: Clock,
) {
    /** Номера обновлений для HTTP-вызовов: от текущего времени, чтобы не пересекаться с номерами Telegram и предыдущих запусков. */
    private val updateIds = AtomicLong(clock.millis())

    /**
     * Принимает вопрос, при включённом автосогласии регистрирует пользователя и передаёт вопрос системе.
     *
     * @param apiKey ключ из заголовка `X-Api-Key`.
     * @param request идентификатор тестового пользователя и текст вопроса.
     * @param trace `true` — добавить в ответ трассу: ветку, запрос после переформулирования, найденные разделы, модель, токены.
     * @return статус, текст ответа, источники и номер записи журнала для оценки.
     */
    @PostMapping("/ask")
    fun ask(
        @RequestHeader("X-Api-Key") apiKey: String?,
        @Valid @RequestBody request: AskRequest,
        @RequestParam(defaultValue = "false") trace: Boolean,
    ): AskResponse {
        authorize(apiKey)
        val userId = TelegramId(request.userId)
        // Блокирующий вызов допустим: это служебный вход, а не путь реальных пользователей.
        val reply = runBlocking {
            if (properties.autoConsent) {
                users.visit(userId, clock.instant())
                users.grantConsent(userId, consent.version, clock.instant())
            }
            core.ask(RagRequest(userId, ChatId(request.userId), UpdateId(updateIds.incrementAndGet()), request.text, clock.instant()))
        }
        return reply.toResponse(withTrace = trace)
    }

    /**
     * Сохраняет оценку ответа от имени тестового пользователя.
     *
     * @param apiKey ключ из заголовка `X-Api-Key`.
     * @param request пользователь, номер ответа, оценка 1–5 и причина.
     * @return `{"saved": true}`, если оценка записана; `false`, если ответ не найден или чужой.
     */
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

    /** Переводит ответ системы в тело HTTP-ответа; трасса добавляется, только если её просили. */
    private fun RagReply.toResponse(withTrace: Boolean) = AskResponse(
        status = status.name,
        text = text,
        sources = sources.map(AnswerSource::toDto),
        answerId = answerId?.value,
        elapsedMs = elapsed.inWholeMilliseconds,
        trace = if (withTrace) trace?.let(TraceDto::of) else null,
    )
}

/** Переводит источник ответа в элемент списка `sources` HTTP-ответа; дата проверки пишется в формате ISO. */
private fun AnswerSource.toDto() = AskResponse.SourceDto(url = url, title = title, checkedAt = checkedAt?.toString())
