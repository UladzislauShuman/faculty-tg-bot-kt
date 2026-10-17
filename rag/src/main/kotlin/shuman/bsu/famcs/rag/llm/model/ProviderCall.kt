package shuman.bsu.famcs.rag.llm.model

/** Что отправляется провайдеру: модель и параметры уже выбраны по настройкам роли. */
data class ProviderCall(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double,
    val topP: Double,
    val maxOutputTokens: Int,
)
