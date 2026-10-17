package shuman.bsu.famcs.rag.api

import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId

/**
 * Оценки ответов пользователями (1–5), по кнопкам под ответом.
 *
 * Оценить можно только свой ответ: номер записи журнала угадывается, поэтому принадлежность проверяется по хешу пользователя.
 * Повторная оценка того же ответа перезаписывает прежнюю.
 */
interface FeedbackService {
    /** Сохраняет оценку. Возвращает `false`, если ответ не найден или принадлежит другому пользователю. */
    suspend fun rate(
        answerId: AnswerId,
        userId: TelegramId,
        rating: Int,
        reason: String? = null,
    ): Boolean
}
