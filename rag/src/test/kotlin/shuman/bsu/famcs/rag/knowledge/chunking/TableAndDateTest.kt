package shuman.bsu.famcs.rag.knowledge.chunking

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.DateExtractor
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.TableRowsFlattener
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Проверка преобразования таблиц в строки и извлечения даты страницы. */
class TableAndDateTest {
    private fun block(html: String) = Jsoup.parse("<div id=\"b\">$html</div>").selectFirst("#b")!!

    @Test
    fun `таблица с заголовком становится абзацами вида колонка двоеточие значение`() {
        val root = block(
            "<table><tr><th>Специальность</th><th>Бюджет</th><th>Платно</th></tr>" +
                "<tr><td>Прикладная математика</td><td>25</td><td>10</td></tr>" +
                "<tr><td>Информатика</td><td>20</td><td></td></tr></table>",
        )

        val count = TableRowsFlattener.flatten(root)

        assertEquals(1, count)
        assertEquals(
            listOf("Специальность: Прикладная математика; Бюджет: 25; Платно: 10", "Специальность: Информатика; Бюджет: 20"),
            root.select("p").map { it.text() },
        )
        assertEquals(0, root.select("table").size)
    }

    @Test
    fun `таблица без заголовка даёт значения через вертикальную черту`() {
        val root = block("<table><tr><td>Орлович Ю. Л.</td><td>декан</td></tr></table>")

        TableRowsFlattener.flatten(root)

        assertEquals(listOf("Орлович Ю. Л. | декан"), root.select("p").map { it.text() })
    }

    @Test
    fun `дата берётся из имени картинки, затем из слов в тексте, затем из цифр`() {
        val withImage = block("<img src=\"/sites/default/files/img/2026-05-14-bsu-sber.jpg\"><p>Событие 3 марта 2020</p>")
        assertEquals(LocalDate.of(2026, 5, 14), DateExtractor.find(withImage, withImage.text()), "картинка надёжнее текста")

        val withWords = block("<p>Заседание состоялось 4 ноября 2022 года.</p>")
        assertEquals(LocalDate.of(2022, 11, 4), DateExtractor.find(withWords, withWords.text()))

        val withDots = block("<p>Приказ от 01.09.2025.</p>")
        assertEquals(LocalDate.of(2025, 9, 1), DateExtractor.find(withDots, withDots.text()))
    }

    @Test
    fun `нет даты или дата невозможная даёт null`() {
        val none = block("<p>Просто текст без дат.</p>")
        assertNull(DateExtractor.find(none, none.text()))
        val impossible = block("<p>31.02.2025</p>")
        assertNull(DateExtractor.find(impossible, impossible.text()))
    }
}
