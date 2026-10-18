package shuman.bsu.famcs.rag.assistant.pipeline.service.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import shuman.bsu.famcs.rag.api.RagService
import shuman.bsu.famcs.rag.api.model.AnswerStatus
import shuman.bsu.famcs.rag.api.model.RagReply
import shuman.bsu.famcs.rag.api.model.RagRequest
import shuman.bsu.famcs.rag.assistant.configuration.properties.BotMessagesProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.limit.exception.OverloadedException
import shuman.bsu.famcs.rag.assistant.limit.model.Admission
import shuman.bsu.famcs.rag.assistant.limit.model.Denial
import shuman.bsu.famcs.rag.assistant.limit.service.RequestLimiter
import shuman.bsu.famcs.rag.assistant.limit.service.WorkQueue
import shuman.bsu.famcs.rag.assistant.pipeline.model.Precheck
import shuman.bsu.famcs.rag.assistant.pipeline.service.AnswerPipeline
import shuman.bsu.famcs.rag.assistant.pipeline.service.AnswerRecorder
import shuman.bsu.famcs.rag.assistant.pipeline.service.Prechecks
import shuman.bsu.famcs.rag.assistant.pipeline.service.ReplyAssembler
import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import shuman.bsu.famcs.rag.llm.service.LlmUsageCollector
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.notification.Alerts
import shuman.bsu.famcs.rag.platform.text.Templates
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * Ядро бота: единственный вход для адаптеров.
 *
 * Порядок: предпроверки → лимиты → место в очереди → конвейер ответа с потолком времени → запись → сборка текста.
 * Гарантия контракта: возвращается ответ с одним из статусов [AnswerStatus], исключение до пользователя не доходит
 * (кроме отмены корутины — она всегда пропускается дальше). На каждый вопрос приходится одна итоговая строка журнала.
 */
class RagServiceImpl(
    private val prechecks: Prechecks,
    private val limits: RequestLimiter,
    private val queueGate: WorkQueue,
    private val pipeline: AnswerPipeline,
    private val recorder: AnswerRecorder,
    private val assembler: ReplyAssembler,
    private val messages: BotMessagesProperties,
    private val queue: LimitProperties.Queue,
    private val alerts: Alerts,
    private val meters: MeterRegistry,
) : RagService {
    /** Итог обработки для итоговой строки журнала: ответ, выбранная ветка и расход токенов. */
    private class Finished(
        val reply: RagReply,
        val route: String? = null,
        val usage: TokenUsage = TokenUsage.ZERO,
    )

    override suspend fun ask(request: RagRequest): RagReply {
        val started = TimeSource.Monotonic.markNow()
        // Номер обновления попадает в каждую строку журнала этого вопроса. Telegram ID и текст вопроса в журнал не пишутся.
        val finished = withContext(MDCContext(mapOf("update" to request.updateId.value.toString()))) {
            try {
                handle(request, started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Необработанная ошибка при ответе на вопрос" }
                Finished(RagReply(AnswerStatus.ERROR, messages.error))
            }
        }
        val reply = finished.reply.copy(elapsed = started.elapsedNow())
        meters.counter("bot.requests", "status", reply.status.name.lowercase()).increment()
        logFinished(reply, finished)
        return reply
    }

    /**
     * Проводит сообщение через предпроверки, лимиты пользователя и очередь.
     *
     * @param request входящее сообщение.
     * @param started момент приёма сообщения: от него считается общее время ответа.
     * @return результат с ответом и израсходованными токенами.
     */
    private suspend fun handle(
        request: RagRequest,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): Finished {
        val question = when (val check = prechecks.check(request)) {
            is Precheck.Rejected -> return Finished(check.reply)
            is Precheck.Accepted -> check.question
        }
        return when (val admission = limits.withAdmission(request.userId) { process(request.userId, question, started) }) {
            is Admission.Denied -> Finished(denied(admission.denial))
            is Admission.Completed -> admission.value
        }
    }

    /**
     * Обрабатывает принятый вопрос внутри очереди: конвейер, сборка текста, запись в журнал.
     * Общий потолок времени стоит вокруг работы, а не вокруг ожидания места в очереди.
     *
     * @param userId Telegram ID автора.
     * @param question очищенный текст вопроса.
     * @param started момент приёма сообщения.
     * @return результат с ответом и израсходованными токенами.
     */
    private suspend fun process(
        userId: TelegramId,
        question: String,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): Finished {
        // Сборщик расхода токенов живёт в контексте корутины: все вызовы моделей этого вопроса попадают в него сами.
        val usage = LlmUsageCollector()
        val result = try {
            queueGate.withSlot {
                // Потолок стоит вокруг работы, а не вокруг ожидания места: ожидание ограничено отдельным сроком очереди.
                withTimeoutOrNull(queue.processingTimeout.toKotlinDuration()) { withContext(usage) { pipeline.run(userId, question) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OverloadedException) {
            log.warn { "Вопрос отклонён: ${e.message}" }
            return failed(userId, AnswerStatus.OVERLOADED, messages.overloaded, usage, started.elapsedNow())
        } catch (e: LlmUnavailableException) {
            log.error { "Все провайдеры моделей отказали: ${e.message}" }
            alerts.raise("llm_unavailable", "Все провайдеры моделей отказали, пользователи получают «перегрузка». ${e.message}")
            return failed(userId, AnswerStatus.OVERLOADED, messages.overloaded, usage, started.elapsedNow())
        } catch (e: Exception) {
            // Сбой Qdrant, Postgres, модели ONNX и прочее внутреннее. Пользователь получает стандартный текст, администратор — оповещение.
            log.error(e) { "Внутренняя ошибка при ответе на вопрос" }
            alerts.raise("internal_error", "Внутренняя ошибка при ответе на вопросы: ${e.javaClass.simpleName}. Подробности в журнале.")
            return failed(userId, AnswerStatus.ERROR, messages.error, usage, started.elapsedNow())
        }
        if (result == null) {
            log.warn { "Вопрос не уложился в ${queue.processingTimeout}" }
            return failed(userId, AnswerStatus.OVERLOADED, messages.overloaded, usage, started.elapsedNow())
        }

        val answerId = recorder.recordAnswer(userId, question, result, usage.total, started.elapsedNow())
        val reply = RagReply(
            status = if (result.output.noInfo) AnswerStatus.NO_INFO else AnswerStatus.ANSWERED,
            text = assembler.assemble(result.output),
            sources = result.output.sources,
            answerId = answerId,
            trace = result.toTrace(usage.total),
        )
        return Finished(reply, route = result.intent.name, usage = usage.total)
    }

    /**
     * Оформляет неудачу (перегрузка или ошибка): записывает её в журнал и возвращает пользователю стандартный текст.
     *
     * @param userId Telegram ID автора.
     * @param status итоговый статус.
     * @param text текст для пользователя.
     * @param usage токены, израсходованные до сбоя.
     * @param elapsed сколько прошло с приёма сообщения.
     */
    private suspend fun failed(
        userId: TelegramId,
        status: AnswerStatus,
        text: String,
        usage: LlmUsageCollector,
        elapsed: Duration,
    ): Finished {
        recorder.recordFailure(userId, status, usage.total, elapsed)
        return Finished(RagReply(status, text), usage = usage.total)
    }

    /**
     * Переводит отказ лимитов в понятный пользователю ответ со статусом «слишком часто» или «перегрузка».
     *
     * @param denial причина отказа: занят предыдущим вопросом, минутный или суточный лимит, бюджет системы.
     */
    private fun denied(denial: Denial): RagReply = when (denial) {
        Denial.Busy -> {
            RagReply(AnswerStatus.RATE_LIMITED, messages.busy)
        }

        is Denial.MinuteLimit -> {
            RagReply(
                AnswerStatus.RATE_LIMITED,
                Templates.fill(
                    messages.rateLimitedMinute,
                    "seconds" to denial.retryAfter.seconds.toString(),
                ),
            )
        }

        is Denial.DayLimit -> {
            RagReply(AnswerStatus.RATE_LIMITED, Templates.fill(messages.rateLimitedDay, "limit" to denial.limit.toString()))
        }

        Denial.SystemBudget -> {
            RagReply(AnswerStatus.OVERLOADED, messages.budgetExhausted)
        }
    }

    /**
     * Одна строка на вопрос: по ней видно, чем всё кончилось, сколько заняло и сколько стоило. Тексты и Telegram ID не пишутся.
     * Молчаливые отказы (дубль, устаревшее) идут на уровне отладки: при потоке повторов они только шумели бы.
     */
    private fun logFinished(
        reply: RagReply,
        finished: Finished,
    ) {
        val fields = mapOf(
            "status" to reply.status.name,
            "route" to (finished.route ?: "-"),
            "rejection" to (reply.rejection?.name ?: "-"),
            "latency_ms" to reply.elapsed.inWholeMilliseconds,
            "tokens_in" to finished.usage.inputTokens,
            "tokens_out" to finished.usage.outputTokens,
        )
        val message = "Вопрос обработан: статус=${reply.status}, ветка=${fields["route"]}, ${fields["latency_ms"]} мс, " +
            "токены ${finished.usage.inputTokens}/${finished.usage.outputTokens}"
        if (reply.deliverable) {
            log.atInfo {
                this.message = message
                payload = fields
            }
        } else {
            log.atDebug {
                this.message = message
                payload = fields
            }
        }
    }
}
