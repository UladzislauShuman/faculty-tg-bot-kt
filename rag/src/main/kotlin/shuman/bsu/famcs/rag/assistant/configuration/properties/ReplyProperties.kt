package shuman.bsu.famcs.rag.assistant.configuration.properties

import jakarta.validation.constraints.NotBlank
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
)
