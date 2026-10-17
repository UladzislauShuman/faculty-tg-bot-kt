package shuman.bsu.famcs.rag.assistant.feedback.service.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.api.FeedbackService
import shuman.bsu.famcs.rag.api.model.RATING_RANGE
import shuman.bsu.famcs.rag.assistant.feedback.repository.FeedbackRepository
import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * Реализация [FeedbackService]: проверяет диапазон оценки и передаёт её в хранилище.
 *
 * @property repository хранилище оценок.
 * @property clock часы, по которым фиксируется момент оценки.
 */
class FeedbackServiceImpl(
    private val repository: FeedbackRepository,
    private val clock: Clock,
) : FeedbackService {
    override suspend fun rate(
        answerId: AnswerId,
        userId: TelegramId,
        rating: Int,
        reason: String?,
    ): Boolean {
        require(rating in RATING_RANGE) { "Оценка должна быть от ${RATING_RANGE.first} до ${RATING_RANGE.last}" }
        val saved = repository.save(answerId, userId, rating, reason, clock.instant())
        if (!saved) log.warn { "Оценка отклонена: ответ ${answerId.value} не найден или чужой" }
        return saved
    }
}
