package shuman.bsu.famcs.rag.assistant.intent.generator.model

/** Ответ генератора по найденным разделам. */
data class GeneratedAnswer(
    val text: String,
    /** Модель сказала, что в разделах ответа нет. */
    val noInfo: Boolean,
    val provider: String,
    val model: String,
)
