package shuman.bsu.famcs.rag.assistant.feedback.repository

import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Instant

/**
 * Хранилище оценок ответов (таблица `feedback`).
 *
 * Оценка привязана к записи журнала ответов; чужой ответ оценить нельзя: принадлежность проверяется по хешу пользователя.
 */
interface FeedbackRepository {
    /**
     * Сохраняет оценку; повторная оценка того же ответа тем же пользователем перезаписывает прежнюю.
     *
     * @param answerId запись журнала, к которой относится оценка.
     * @param userId автор оценки; ответ должен принадлежать ему.
     * @param rating оценка (диапазон проверяет сервис).
     * @param reason причина низкой оценки, если пользователь её указал.
     * @param at момент оценки.
     * @return `true`, если оценка записана; `false`, если ответ не найден или принадлежит другому пользователю.
     */
    suspend fun save(
        answerId: AnswerId,
        userId: TelegramId,
        rating: Int,
        reason: String?,
        at: Instant,
    ): Boolean
}
