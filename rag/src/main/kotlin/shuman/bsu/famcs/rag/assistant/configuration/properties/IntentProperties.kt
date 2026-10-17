package shuman.bsu.famcs.rag.assistant.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки классификатора (`bot.intent`). */
@Validated
@ConfigurationProperties("bot.intent")
data class IntentProperties(
    val mode: Mode,
) {
    enum class Mode {
        /** Классификатор выключен: все вопросы идут в RAG, как в базовой версии. */
        OFF,

        /** Ветку выбирает модель с ролью CLASSIFIER; чистые приветствия определяются правилом без вызова модели. */
        LLM,
    }
}
