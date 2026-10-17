package shuman.bsu.famcs.rag.pipeline

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import shuman.bsu.famcs.rag.common.Templates
import shuman.bsu.famcs.rag.core.BotMessages
import shuman.bsu.famcs.rag.intent.HandlerOutput
import java.time.format.DateTimeFormatter

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

/**
 * Собирает текст для пользователя из ответа обработчика: ответ, источники с датой проверки, оговорка для дат.
 *
 * Пометка «проверено» берётся из индексатора: это дата, когда страница последний раз сверялась с сайтом,
 * а не дата самой публикации. Ответ «не нашёл» источников не показывает, вместо них — подсказка, где искать самому.
 */
class ReplyAssembler(
    private val messages: BotMessages,
    properties: ReplyProperties,
) {
    private val titleSeparator = properties.titleSeparator
    private val caveat = Regex(properties.dateCaveatPattern, RegexOption.IGNORE_CASE)
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun assemble(output: HandlerOutput): String = buildString {
        append(output.text)
        when {
            output.noInfo -> {
                if (messages.noInfoHint !in output.text) append("\n\n").append(messages.noInfoHint)
            }

            else -> {
                if (output.sources.isNotEmpty()) {
                    append("\n\n").append(messages.sourcesTitle)
                    output.sources.forEach { source ->
                        append("\n• ")
                        source.title?.let { append(it.substringBefore(titleSeparator).trim()).append(" — ") }
                        append(source.url)
                        source.checkedAt?.let {
                            append(
                                " (",
                            ).append(Templates.fill(messages.checkedOn, "date" to it.format(dateFormat))).append(")")
                        }
                    }
                }
                if (caveat.containsMatchIn(output.text)) append("\n\n").append(messages.dateCaveat)
            }
        }
    }
}
