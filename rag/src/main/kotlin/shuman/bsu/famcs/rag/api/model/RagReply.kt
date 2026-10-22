package shuman.bsu.famcs.rag.api.model

import shuman.bsu.famcs.rag.platform.identifier.AnswerId
import kotlin.time.Duration

/** Ответ ядра. */
data class RagReply(
    val status: AnswerStatus,
    /** Готовый текст для пользователя. Пустой при молчаливом отказе. */
    val text: String,
    val sources: List<AnswerSource> = emptyList(),
    /** Идентификатор записи журнала. Есть только у ответов, которые можно оценить. */
    val answerId: AnswerId? = null,
    val rejection: RejectionReason? = null,
    val elapsed: Duration = Duration.ZERO,
    /** Что система сделала с вопросом по шагам. Есть только у обработанных вопросов. Telegram её не показывает. */
    val trace: AnswerTrace? = null,
) {
    /** Нужно ли отправлять что-то пользователю. */
    val deliverable: Boolean get() = rejection?.silent != true

    companion object {
        /** Молчаливый отказ: дубль или устаревшее обновление. */
        fun silent(reason: RejectionReason): RagReply =
            RagReply(status = AnswerStatus.REJECTED, text = "", rejection = reason)
    }
}
