package shuman.bsu.famcs.rag.assistant.configuration.properties

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
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
    /** Сколько близких страниц предлагать под ответом «не нашёл». 0 — не предлагать. */
    @field:Min(0) val maxSuggestions: Int = 0,
    /** Наименьшая оценка реранкера (с поправкой по типу страницы), при которой страница предлагается. Мусорные оценки около 0,01. */
    @field:DecimalMin("0.0") val suggestionMinScore: Double = 0.0,
    /**
     * Другие фразы отказа, которые тоже считаются «не нашёл». Модель иногда повторяет формулировку из истории диалога
     * («Не нашёл в документах факультета…»), и без этого под отказом показывались бы случайные источники. Проверка при запуске к ним не применяется.
     */
    val extraNoInfoMarkers: List<String> = emptyList(),
)
