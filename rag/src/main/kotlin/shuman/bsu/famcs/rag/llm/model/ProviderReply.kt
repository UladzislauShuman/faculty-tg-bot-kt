package shuman.bsu.famcs.rag.llm.model

/** Что провайдер вернул. `usage == null`, если провайдер не сообщил расход токенов. */
data class ProviderReply(
    val text: String,
    val usage: TokenUsage?,
    val model: String,
)
