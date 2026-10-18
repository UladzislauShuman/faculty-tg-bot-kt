package shuman.bsu.famcs.rag.assistant.pipeline.model

import shuman.bsu.famcs.rag.api.model.AnswerTrace
import shuman.bsu.famcs.rag.api.model.TraceCandidate
import shuman.bsu.famcs.rag.api.model.TracePassage
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.llm.model.TokenUsage

/** Что получилось из вопроса: контекст диалога, выбранная ветка, запрос для поиска и ответ обработчика. */
data class PipelineResult(
    val context: DialogContext,
    val intent: Intent,
    /** Самостоятельная формулировка вопроса (равна исходному, если переформулировки не было). */
    val query: String,
    val output: HandlerOutput,
) {
    /**
     * Собирает трассу ответа для проверок качества.
     *
     * @param usage токены, израсходованные на весь вопрос.
     */
    fun toTrace(usage: TokenUsage) = AnswerTrace(
        route = intent.name,
        query = query,
        passages = output.passages.map { TracePassage(it.url, it.title, it.parentId, it.relevance, it.text) },
        candidates = output.candidates.map {
            TraceCandidate(
                it.url,
                it.title,
                it.relevance,
                it.parentChars,
                it.matchedFragment,
                it.selected,
            )
        },
        searchMs = output.searchMillis,
        rerankMs = output.rerankMillis,
        generationMs = output.generationMillis,
        provider = output.provider,
        model = output.model,
        tokensIn = usage.inputTokens,
        tokensOut = usage.outputTokens,
    )
}
