package shuman.bsu.famcs.rag.knowledge.chunking.implementation

import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Достаёт дату страницы, когда сайт не отдаёт её в разметке (у 66% новостей даты в метаданных нет).
 *
 * Источники по порядку: дата в имени картинки (`2026-05-14-название.jpg`, на сайте так называют картинки новостей),
 * затем первая дата в тексте («14 мая 2026» или `14.05.2026`). Это оценка, а не точная дата публикации: в тексте может стоять дата
 * события. Поэтому она подписывается как дата страницы и используется только как подсказка модели и для порядка новостей.
 */
object DateExtractor {
    private val MONTHS =
        listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val IN_FILE = Regex("(?<!\\d)((?:19|20)\\d\\d)-(\\d\\d)-(\\d\\d)(?!\\d)")
    private val WORDS = Regex("(\\d{1,2})\\s+(${MONTHS.joinToString("|")})\\s+((?:19|20)\\d\\d)")
    private val DOTS = Regex("(?<!\\d)(\\d{1,2})\\.(\\d{2})\\.((?:19|20)\\d\\d)(?!\\d)")

    /**
     * Находит дату страницы.
     *
     * @param block блок содержимого страницы (для имён картинок).
     * @param text текст блока.
     * @return дата или `null`, если не найдена.
     */
    fun find(
        block: Element,
        text: String,
    ): LocalDate? {
        block.select("img[src]").forEach { image ->
            IN_FILE.find(image.attr("src"))?.let { m -> date(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it } }
        }
        WORDS.find(text)?.let { m ->
            date(m.groupValues[3], (MONTHS.indexOf(m.groupValues[2]) + 1).toString(), m.groupValues[1])?.let { return it }
        }
        DOTS.find(text)?.let { m -> date(m.groupValues[3], m.groupValues[2], m.groupValues[1])?.let { return it } }
        return null
    }

    /** Собирает дату из частей; `null`, если такой даты не бывает. */
    private fun date(
        year: String,
        month: String,
        day: String,
    ): LocalDate? = try {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    } catch (_: DateTimeParseException) {
        null
    } catch (_: java.time.DateTimeException) {
        null
    }
}
