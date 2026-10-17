package shuman.bsu.famcs.rag.assistant.pipeline.service

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.api.model.AnswerStatus
import shuman.bsu.famcs.rag.api.model.RagReply
import shuman.bsu.famcs.rag.api.model.RagRequest
import shuman.bsu.famcs.rag.api.model.RejectionReason
import shuman.bsu.famcs.rag.assistant.configuration.properties.BotMessagesProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.assistant.pipeline.model.Precheck
import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.platform.text.Templates
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Дешёвые проверки до всякой работы с моделями: ни один отказ здесь не тратит токены.
 *
 * Порядок: возраст сообщения, повторная доставка, регистрация пользователя и согласие, режим обслуживания, тип и длина текста.
 * Возраст и дубль идут первыми и отвечают молча: на вчерашние сообщения после простоя и на повторы отвечать не нужно.
 */
class Prechecks(
    private val processed: ProcessedUpdateRepository,
    private val users: UserService,
    private val maintenance: MaintenanceSwitch,
    private val consent: ConsentProperties,
    private val updates: LimitProperties.Updates,
    private val input: LimitProperties.Input,
    private val messages: BotMessagesProperties,
    private val clock: Clock,
) {
    /**
     * Проверяет сообщение до любой дорогой работы: возраст, повторная доставка, согласие,
     * режим обслуживания, тип и длина. Порядок важен: сначала молчаливые отказы (старое, повторное), потом те, о которых нужно сообщить пользователю.
     *
     * @param request входящее сообщение.
     * @return [Precheck.Accepted] с очищенным текстом вопроса или [Precheck.Rejected] с готовым ответом.
     */
    suspend fun check(request: RagRequest): Precheck {
        val now = clock.instant()
        if (Duration.between(request.sentAt, now) > updates.maxAge) {
            log.info { "Сообщение пропущено: старше ${updates.maxAge}" }
            return Precheck.Rejected(RagReply.silent(RejectionReason.STALE))
        }
        if (!processed.registerIfNew(request.updateId.value, now)) {
            log.info { "Сообщение пропущено: повторная доставка обновления ${request.updateId.value}" }
            return Precheck.Rejected(RagReply.silent(RejectionReason.DUPLICATE))
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

    /**
     * Собирает отказ со статусом REJECTED и указанной причиной.
     *
     * @param reason причина отказа.
     * @param text текст, который увидит пользователь.
     */
    private fun rejected(
        reason: RejectionReason,
        text: String,
    ) = Precheck.Rejected(RagReply(status = AnswerStatus.REJECTED, text = text, rejection = reason))
}
