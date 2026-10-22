package shuman.bsu.famcs.rag.assistant.pipeline.service

import shuman.bsu.famcs.rag.assistant.configuration.properties.BotMessagesProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.ReplyProperties
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.platform.text.Templates
import java.time.format.DateTimeFormatter

/**
 * Собирает текст для пользователя из ответа обработчика: ответ, источники с датой проверки, оговорка для дат.
 *
 * Пометка «проверено» берётся из индексатора: это дата, когда страница последний раз сверялась с сайтом,
 * а не дата самой публикации. Ответ «не нашёл» источников не показывает, вместо них — подсказка, где искать самому.
 */
class ReplyAssembler(
    private val messages: BotMessagesProperties,
    properties: ReplyProperties,
) {
    private val titleSeparator = properties.titleSeparator
    private val caveat = Regex(properties.dateCaveatPattern, RegexOption.IGNORE_CASE)
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * Собирает итоговый текст для пользователя: ответ модели, затем пояснение или список источников с датой проверки
     * и оговорка про даты, если в ответе встретились сроки и суммы.
     *
     * @param output результат обработчика ветки.
     * @return готовый текст сообщения.
     */
    fun assemble(output: HandlerOutput): String = buildString {
        append(output.text)
        when {
            output.noInfo -> {
                if (messages.noInfoHint !in output.text) append("\n\n").append(messages.noInfoHint)
                if (output.suggestions.isNotEmpty()) {
                    append("\n\n").append(messages.suggestionsTitle)
                    output.suggestions.forEach { page ->
                        append("\n• ")
                        page.title?.let { append(it.substringBefore(titleSeparator).trim()).append(" — ") }
                        append(page.url)
                    }
                }
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
