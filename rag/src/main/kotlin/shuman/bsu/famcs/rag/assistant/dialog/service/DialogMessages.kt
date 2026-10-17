package shuman.bsu.famcs.rag.assistant.dialog.service

import shuman.bsu.famcs.rag.assistant.dialog.model.Author
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.llm.model.LlmMessage

/**
 * Собирает сообщения для модели: системная инструкция, сводка старого диалога, свежие реплики, текущий текст пользователя.
 *
 * Сводка идёт внутри системного сообщения, а не отдельным: часть провайдеров принимает только одно системное сообщение.
 */
fun DialogContext.toMessages(
    systemPrompt: String,
    userText: String,
): List<LlmMessage> = buildList {
    add(
        LlmMessage.system(
            if (summary.isNullOrBlank()) systemPrompt else "$systemPrompt\n\nКраткое содержание более раннего диалога: $summary",
        ),
    )
    history.forEach { add(if (it.author == Author.USER) LlmMessage.user(it.text) else LlmMessage.assistant(it.text)) }
    add(LlmMessage.user(userText))
}
