package shuman.bsu.famcs.rag.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UserHasher
import shuman.bsu.famcs.rag.core.AnswerStatus
import shuman.bsu.famcs.rag.dialog.ContextManager
import shuman.bsu.famcs.rag.feedback.AnswerLog
import shuman.bsu.famcs.rag.feedback.AnswerLogEntry
import shuman.bsu.famcs.rag.limits.UsageStore
import shuman.bsu.famcs.rag.llm.TokenUsage
import java.time.Clock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Запись итога обработки: реплики диалога, журнал ответа и суточные счётчики.
 *
 * Запись — после ответа и «по возможности»: если база не приняла запись, пользователь всё равно получает уже готовый ответ,
 * просто без кнопок оценки. Терять ответ из-за сбоя журнала хуже, чем потерять строку журнала.
 */
class AnswerRecorder(
    private val contexts: ContextManager,
    private val answers: AnswerLog,
    private val usage: UsageStore,
    private val hasher: UserHasher,
    private val clock: Clock,
) {
    /** Записывает успешный ответ. Возвращает номер записи журнала или `null`, если запись не удалась. */
    suspend fun recordAnswer(
        userId: TelegramId,
        question: String,
        result: PipelineResult,
        tokens: TokenUsage,
        elapsed: Duration,
    ): AnswerId? = guarded("запись ответа") {
        val output = result.output
        val messageId = contexts.record(result.context, question, output.text)
        val answerId = answers.append(
            AnswerLogEntry(
                messageId = messageId,
                userHash = hasher.hash(userId),
                query = result.query,
                route = result.intent,
                status = if (output.noInfo) AnswerStatus.NO_INFO else AnswerStatus.ANSWERED,
                noInfo = output.noInfo,
                sources = output.sources.map { it.url },
                provider = output.provider,
                model = output.model,
                tokensIn = tokens.inputTokens,
                tokensOut = tokens.outputTokens,
                latencyMs = elapsed.inWholeMilliseconds.toInt(),
                createdAt = clock.instant(),
            ),
        )
        // Вопрос засчитывается в суточный лимит только после ответа: сбои бота не должны отнимать у человека вопросы.
        usage.recordMessage(userId, LocalDate.now(clock), tokens.totalTokens.toLong())
        answerId
    }

    /** Записывает неудачную обработку (перегрузка, ошибка): строка нужна для статистики, диалог не меняется и вопрос не засчитывается. */
    suspend fun recordFailure(
        userId: TelegramId,
        status: AnswerStatus,
        tokens: TokenUsage,
        elapsed: Duration,
    ) {
        guarded("запись сбоя") {
            answers.append(
                AnswerLogEntry(
                    messageId = null,
                    userHash = hasher.hash(userId),
                    query = null,
                    route = null,
                    status = status,
                    noInfo = false,
                    sources = emptyList(),
                    provider = null,
                    model = null,
                    tokensIn = tokens.inputTokens,
                    tokensOut = tokens.outputTokens,
                    latencyMs = elapsed.inWholeMilliseconds.toInt(),
                    createdAt = clock.instant(),
                ),
            )
        }
    }

    private suspend fun <T> guarded(
        what: String,
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "Не удалось выполнить: $what" }
        null
    }
}
