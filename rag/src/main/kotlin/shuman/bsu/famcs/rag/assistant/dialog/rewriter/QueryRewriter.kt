package shuman.bsu.famcs.rag.assistant.dialog.rewriter

import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext

/**
 * Превращает вопрос в самостоятельный запрос для поиска.
 *
 * Зачем: «А какой там проходной балл?» без истории ничего не значит ни для векторного поиска, ни для классификатора.
 * Переформулировка стоит перед классификатором (решение автора): так он видит самостоятельный вопрос.
 */
fun interface QueryRewriter {
    /** Возвращает самостоятельную формулировку. При любом сбое возвращает исходный вопрос: поиск ухудшится, но ответ будет. */
    suspend fun rewrite(
        question: String,
        context: DialogContext,
    ): String
}
