package shuman.bsu.famcs.rag.llm.model

import kotlin.time.Duration

/** Ответ модели вместе с тем, кто его дал и сколько это стоило. */
data class LlmReply(
    val text: String,
    val usage: TokenUsage,
    val provider: String,
    val model: String,
    val elapsed: Duration,
)
