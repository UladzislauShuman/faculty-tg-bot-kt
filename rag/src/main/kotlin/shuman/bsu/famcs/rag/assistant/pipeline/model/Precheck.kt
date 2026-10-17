package shuman.bsu.famcs.rag.assistant.pipeline.model

import shuman.bsu.famcs.rag.api.model.RagReply

/** Итог предпроверок: вопрос принят (с очищенным текстом) или отклонён готовым ответом. */
sealed interface Precheck {
    data class Accepted(val question: String) : Precheck

    data class Rejected(val reply: RagReply) : Precheck
}
