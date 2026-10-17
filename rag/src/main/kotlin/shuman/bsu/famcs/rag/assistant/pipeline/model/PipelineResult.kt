package shuman.bsu.famcs.rag.assistant.pipeline.model

import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.assistant.intent.model.Intent

/** Что получилось из вопроса: контекст диалога, выбранная ветка, запрос для поиска и ответ обработчика. */
data class PipelineResult(
    val context: DialogContext,
    val intent: Intent,
    /** Самостоятельная формулировка вопроса (равна исходному, если переформулировки не было). */
    val query: String,
    val output: HandlerOutput,
)
