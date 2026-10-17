package shuman.bsu.famcs.rag.llm.model

/** Запрос к модели. Параметры генерации (температура, лимит ответа, таймаут) берутся из настроек роли. */
data class LlmRequest(
    val role: LlmRole,
    val messages: List<LlmMessage>,
) {
    init {
        require(messages.isNotEmpty()) { "Запрос к модели должен содержать хотя бы одно сообщение" }
    }
}
