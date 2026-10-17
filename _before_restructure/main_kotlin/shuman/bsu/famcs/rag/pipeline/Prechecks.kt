package shuman.bsu.famcs.rag.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.common.Templates
import shuman.bsu.famcs.rag.core.AnswerStatus
import shuman.bsu.famcs.rag.core.BotMessages
import shuman.bsu.famcs.rag.core.CoreReply
import shuman.bsu.famcs.rag.core.CoreRequest
import shuman.bsu.famcs.rag.core.MaintenanceSwitch
import shuman.bsu.famcs.rag.core.RejectionReason
import shuman.bsu.famcs.rag.dialog.ConsentProperties
import shuman.bsu.famcs.rag.dialog.UserDirectory
import shuman.bsu.famcs.rag.limits.LimitsProperties
import shuman.bsu.famcs.rag.limits.ProcessedUpdates
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/** Итог предпроверок: вопрос принят (с очищенным текстом) или отклонён готовым ответом. */
sealed interface Precheck {
    data class Accepted(val question: String) : Precheck

    data class Rejected(val reply: CoreReply) : Precheck
}

/**
 * Дешёвые проверки до всякой работы с моделями: ни один отказ здесь не тратит токены.
 *
 * Порядок: возраст сообщения, повторная доставка, регистрация пользователя и согласие, режим обслуживания, тип и длина текста.
 * Возраст и дубль идут первыми и отвечают молча: на вчерашние сообщения после простоя и на повторы отвечать не нужно.
 */
class Prechecks(
    private val processed: ProcessedUpdates,
    private val users: UserDirectory,
    private val maintenance: MaintenanceSwitch,
    private val consent: ConsentProperties,
    private val updates: LimitsProperties.Updates,
    private val input: LimitsProperties.Input,
    private val messages: BotMessages,
    private val clock: Clock,
) {
    suspend fun check(request: CoreRequest): Precheck {
        val now = clock.instant()
        if (Duration.between(request.sentAt, now) > updates.maxAge) {
            log.info { "Сообщение пропущено: старше ${updates.maxAge}" }
            return Precheck.Rejected(CoreReply.silent(RejectionReason.STALE))
        }
        if (!processed.registerIfNew(request.updateId.value, now)) {
            log.info { "Сообщение пропущено: повторная доставка обновления ${request.updateId.value}" }
            return Precheck.Rejected(CoreReply.silent(RejectionReason.DUPLICATE))
        }
        // Запись пользователя нужна и тем, кто ещё не дал согласия: к ней привязаны счётчики и сам факт согласия.
        if (!users.visit(request.userId, now).hasConsent(consent.version)) {
            return rejected(RejectionReason.CONSENT_REQUIRED, messages.consentRequired)
        }
        if (maintenance.enabled) return rejected(RejectionReason.MAINTENANCE, messages.maintenance)

        val text = request.text?.trim()
        if (text.isNullOrEmpty()) return rejected(RejectionReason.UNSUPPORTED_INPUT, messages.unsupportedInput)
        if (text.length > input.maxQuestionChars) {
            return rejected(RejectionReason.TOO_LONG, Templates.fill(messages.tooLong, "limit" to input.maxQuestionChars.toString()))
        }
        return Precheck.Accepted(text)
    }

    private fun rejected(
        reason: RejectionReason,
        text: String,
    ) = Precheck.Rejected(CoreReply(status = AnswerStatus.REJECTED, text = text, rejection = reason))
}
