package shuman.bsu.famcs.rag.assistant.feedback.repository

import shuman.bsu.famcs.rag.assistant.feedback.model.AnswerLogEntry
import shuman.bsu.famcs.rag.platform.identifier.AnswerId

/** Журнал ответов. */
interface AnswerLogRepository {
    /** Добавляет запись и возвращает её номер: к нему привязываются кнопки оценки. */
    suspend fun append(entry: AnswerLogEntry): AnswerId
}
