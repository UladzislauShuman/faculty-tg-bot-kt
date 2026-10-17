package shuman.bsu.famcs.rag.llm.model

/** Расход токенов одним вызовом. Нужен для лимитов, бюджета и метрик. */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    companion object {
        val ZERO = TokenUsage(inputTokens = 0, outputTokens = 0)
    }
}
