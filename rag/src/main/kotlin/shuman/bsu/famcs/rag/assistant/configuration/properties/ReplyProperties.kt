package shuman.bsu.famcs.rag.assistant.configuration.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки сборки ответа (`bot.reply`). */
@Validated
@ConfigurationProperties("bot.reply")
data class ReplyProperties(
    /**
     * Регулярное выражение для ответов, где нужна оговорка «проверьте актуальность»: даты, сроки, оплата.
     * Эвристика: ловит лишнее чаще, чем пропускает. Оговорка дешевле, чем неверный срок подачи документов.
     */
    @field:NotBlank val dateCaveatPattern: String,
    /**
     * Заголовки страниц сайта имеют вид «Раздел | Название сайта». В ответе остаётся часть до этого разделителя:
     * название сайта в каждой строке источников только удлиняет сообщение.
     */
    @field:NotBlank val titleSeparator: String,
    /** Строка стоимости вопроса под ответом. */
    @field:Valid val cost: Cost = Cost(),
) {
    /**
     * Показ стоимости вопроса. Тариф относится к модели, на которой работает бот, и задаётся вместе с ней (например, в профиле `qwen`).
     *
     * @property enabled показывать ли строку.
     * @property inputPerMillion цена 1 млн входных токенов.
     * @property outputPerMillion цена 1 млн выходных токенов.
     * @property currency обозначение валюты в строке.
     * @property template вид строки; метки `{cost}`, `{currency}`, `{input}`, `{output}`.
     */
    data class Cost(
        val enabled: Boolean = false,
        @field:PositiveOrZero val inputPerMillion: Double = 0.0,
        @field:PositiveOrZero val outputPerMillion: Double = 0.0,
        @field:NotBlank val currency: String = "$",
        @field:NotBlank val template: String = "Цена вопроса ≈ {cost} {currency} ({input} токенов на вход, {output} на выход)",
    )
}
