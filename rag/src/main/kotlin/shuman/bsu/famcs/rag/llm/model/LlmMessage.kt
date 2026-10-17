package shuman.bsu.famcs.rag.llm.model

import shuman.bsu.famcs.rag.assistant.dialog.model.Author

/** Одно сообщение в запросе к модели. */
data class LlmMessage(
    val author: Author,
    val content: String,
) {
    enum class Author { SYSTEM, USER, ASSISTANT }

    companion object {
        /** Системное сообщение: инструкция модели, как себя вести. */
        fun system(content: String) = LlmMessage(Author.SYSTEM, content)

        /** Сообщение пользователя. */
        fun user(content: String) = LlmMessage(Author.USER, content)

        /** Прежний ответ модели (для истории диалога). */
        fun assistant(content: String) = LlmMessage(Author.ASSISTANT, content)
    }
}
