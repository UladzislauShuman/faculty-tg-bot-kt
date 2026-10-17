package shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation

import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.QueryRewriter

/** Переформулирование выключено (`mode: OFF`): вопрос идёт в поиск как есть. */
object IdentityQueryRewriter : QueryRewriter {
    override suspend fun rewrite(
        question: String,
        context: DialogContext,
    ) = question
}
