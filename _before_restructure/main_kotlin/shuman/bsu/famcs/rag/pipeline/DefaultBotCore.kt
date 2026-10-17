package shuman.bsu.famcs.rag.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.Templates
import shuman.bsu.famcs.rag.core.Alerts
import shuman.bsu.famcs.rag.core.AnswerStatus
import shuman.bsu.famcs.rag.core.BotCore
import shuman.bsu.famcs.rag.core.BotMessages
import shuman.bsu.famcs.rag.core.CoreReply
import shuman.bsu.famcs.rag.core.CoreRequest
import shuman.bsu.famcs.rag.limits.Admission
import shuman.bsu.famcs.rag.limits.Denial
import shuman.bsu.famcs.rag.limits.LimitsProperties
import shuman.bsu.famcs.rag.limits.OverloadedException
import shuman.bsu.famcs.rag.limits.RequestLimiter
import shuman.bsu.famcs.rag.limits.WorkQueue
import shuman.bsu.famcs.rag.llm.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.LlmUsageCollector
import shuman.bsu.famcs.rag.llm.TokenUsage
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
class DefaultBotCore(
    private val prechecks: Prechecks,
    private val limits: RequestLimiter,
    private val queueGate: WorkQueue,
    private val pipeline: AnswerPipeline,
    private val recorder: AnswerRecorder,
    private val assembler: ReplyAssembler,
    private val messages: BotMessages,
    private val queue: LimitsProperties.Queue,
    private val alerts: Alerts,
    private val meters: MeterRegistry,
) : BotCore {
    /** Итог обработки для итоговой строки журнала: ответ, выбранная ветка и расход токенов. */
    private class Finished(
        val reply: CoreReply,
        val route: String? = null,
        val usage: TokenUsage = TokenUsage.ZERO,
    )

    override suspend fun ask(request: CoreRequest): CoreReply {
        val started = TimeSource.Monotonic.markNow()
        // Номер обновления попадает в каждую строку журнала этого вопроса. Telegram ID и текст вопроса в журнал не пишутся.
        val finished = withContext(MDCContext(mapOf("update" to request.updateId.value.toString()))) {
            try {
                handle(request, started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Необработанная ошибка при ответе на вопрос" }
                Finished(CoreReply(AnswerStatus.ERROR, messages.error))
            }
        }
        val reply = finished.reply.copy(elapsed = started.elapsedNow())
        meters.counter("bot.requests", "status", reply.status.name.lowercase()).increment()
        logFinished(reply, finished)
        return reply
    }

    private suspend fun handle(
        request: CoreRequest,
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
        val reply = CoreReply(
            status = if (result.output.noInfo) AnswerStatus.NO_INFO else AnswerStatus.ANSWERED,
            text = assembler.assemble(result.output),
            sources = result.output.sources,
            answerId = answerId,
        )
        return Finished(reply, route = result.intent.name, usage = usage.total)
    }

    private suspend fun failed(
        userId: TelegramId,
        status: AnswerStatus,
        text: String,
        usage: LlmUsageCollector,
        elapsed: Duration,
    ): Finished {
        recorder.recordFailure(userId, status, usage.total, elapsed)
        return Finished(CoreReply(status, text), usage = usage.total)
    }

    private fun denied(denial: Denial): CoreReply = when (denial) {
        Denial.Busy -> {
            CoreReply(AnswerStatus.RATE_LIMITED, messages.busy)
        }

        is Denial.MinuteLimit -> {
            CoreReply(
                AnswerStatus.RATE_LIMITED,
                Templates.fill(
                    messages.rateLimitedMinute,
                    "seconds" to denial.retryAfter.seconds.toString(),
                ),
            )
        }

        is Denial.DayLimit -> {
            CoreReply(AnswerStatus.RATE_LIMITED, Templates.fill(messages.rateLimitedDay, "limit" to denial.limit.toString()))
        }

        Denial.SystemBudget -> {
            CoreReply(AnswerStatus.OVERLOADED, messages.budgetExhausted)
        }
    }

    /**
     * Одна строка на вопрос: по ней видно, чем всё кончилось, сколько заняло и сколько стоило. Тексты и Telegram ID не пишутся.
     * Молчаливые отказы (дубль, устаревшее) идут на уровне отладки: при потоке повторов они только шумели бы.
     */
    private fun logFinished(
        reply: CoreReply,
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
