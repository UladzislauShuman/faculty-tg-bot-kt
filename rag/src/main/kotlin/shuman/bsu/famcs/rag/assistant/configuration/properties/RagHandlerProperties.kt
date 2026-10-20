package shuman.bsu.famcs.rag.assistant.configuration.properties

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки ветки RAG (`bot.rag`). */
@Validated
@ConfigurationProperties("bot.rag")
data class RagHandlerProperties(
    /**
     * Фраза, по которой ответ модели считается «не нашёл». Должна совпадать с фразой из `prompts/answer-system.txt`:
     * при расхождении приложение не запустится. Сравнение без учёта регистра и лишних пробелов.
     */
    @field:NotBlank val noInfoMarker: String,
    /** Сколько источников показывать под ответом. */
    @field:Positive val maxSources: Int,
    /**
     * Подписывать каждый раздел в промпте: «[Источник: название страницы, год]». Модель видит, из какой страницы и какого года
     * текст, и при противоречии может выбрать более новый. Без подписи разделы склеены без указания источника.
     */
    val passageHeader: Boolean = false,
)
