package shuman.bsu.famcs.rag.knowledge.chunking

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.HtmlToMarkdown
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.MarkdownHeaderSplitter
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.ParentChildHtmlChunker
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.RecursiveCharacterSplitter
import shuman.bsu.famcs.rag.knowledge.model.FetchedDocument
import shuman.bsu.famcs.rag.knowledge.model.PageType
import shuman.bsu.famcs.rag.testsupport.Parity
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Паритет чанкера: настоящая страница сайта (`graduate_page.html`) режется так же, как это делала Python-версия.
 * Эталон — parity/chunker_graduate.json. Сравнение без учёта пробелов: оригинал удалял неразрывные пробелы,
 * Kotlin заменяет их обычными (намеренное улучшение).
 */
class ParentChildHtmlChunkerParityTest {
    private val chunker = ParentChildHtmlChunker(
        converter = HtmlToMarkdown(escapeAsterisks = true, escapeUnderscores = true, bullets = "*+-", absolutizeLinks = false),
        headerSplitter = MarkdownHeaderSplitter(
            listOf(
                MarkdownHeaderSplitter.Header("#", "H1"),
                MarkdownHeaderSplitter.Header("##", "H2"),
                MarkdownHeaderSplitter.Header("###", "H3"),
            ),
        ),
        childSplitter = RecursiveCharacterSplitter(chunkSize = 300, chunkOverlap = 50, separators = listOf("\n\n", "\n", ".", " ", "")),
        contentSelectors = listOf("div#block-famcs-content", "article"),
        removedTags = listOf("script", "form", "nav", "header", "footer"),
    )

    private val document = FetchedDocument(
        url = "https://fpmi.bsu.by/graduate",
        mediaType = "text/html",
        body = Parity.text("graduate_page.html"),
        fetchedAt = Instant.parse("2026-10-08T00:00:00Z"),
    )

    private fun String.squash() = filterNot { it.isWhitespace() }

    @Test
    fun `страница делится на те же разделы с теми же заголовками`() {
        val expected = Parity.json("chunker_graduate.json")["parents"]
        val actual = chunker.chunk(document).parents

        assertEquals(expected.size(), actual.size, "число родителей")
        for ((index, parent) in actual.withIndex()) {
            assertEquals(expected[index]["metadata"]["H2"].asString(), parent.headings["H2"], "заголовок раздела $index")
        }
    }

    @Test
    fun `текст родителей совпадает с Python с точностью до пробелов`() {
        val expected = Parity.json("chunker_graduate.json")["parents"]
        val actual = chunker.chunk(document).parents

        for ((index, parent) in actual.withIndex()) {
            assertEquals(expected[index]["text"].asString().squash(), parent.text.squash(), "текст раздела $index")
        }
    }

    @Test
    fun `дети совпадают по количеству и тексту с точностью до пробелов`() {
        val expected = Parity.json("chunker_graduate.json")["children"]
        val actual = chunker.chunk(document).children

        assertEquals(expected.size(), actual.size, "число детей")
        for ((index, child) in actual.withIndex()) {
            assertEquals(expected[index]["text"].asString().squash(), child.text.squash(), "дочерний чанк $index")
        }
    }

    @Test
    fun `идентификаторы детерминированы и ребёнок указывает на существующего родителя`() {
        val first = chunker.chunk(document)
        val second = chunker.chunk(document)

        assertEquals(first.children.map { it.id }, second.children.map { it.id })
        assertTrue(first.children.all { first.parentOf(it) != null })
    }
}

/** Ограничение размера родителя: настройка, которой в Python нет, поэтому проверяется отдельно от паритета. */
class ParentSizeLimitTest {
    private fun chunker(
        maxParentChars: Int,
        tablesAsRows: Boolean = false,
        wholePageMaxChars: Int = 0,
    ) = ParentChildHtmlChunker(
        converter = HtmlToMarkdown(escapeAsterisks = true, escapeUnderscores = true, bullets = "*+-", absolutizeLinks = false),
        headerSplitter = MarkdownHeaderSplitter(listOf(MarkdownHeaderSplitter.Header("##", "H2"))),
        childSplitter = RecursiveCharacterSplitter(chunkSize = 300, chunkOverlap = 50, separators = listOf("\n\n", "\n", ".", " ", "")),
        contentSelectors = listOf("div#block-famcs-content"),
        removedTags = listOf("script"),
        maxParentChars = maxParentChars,
        tablesAsRows = tablesAsRows,
        wholePageMaxChars = wholePageMaxChars,
    )

    private val chronicle = FetchedDocument(
        url = "https://fpmi.bsu.by/history-1997",
        mediaType = "text/html",
        body = "<html><head><title>История 1997</title></head><body><div id=\"block-famcs-content\"><h2>Хроника</h2>" +
            List(30) { "<p>Событие номер $it: на факультете произошло важное дело, о котором помнят.</p>" }.joinToString("") +
            "</div></body></html>",
        fetchedAt = Instant.parse("2026-10-08T00:00:00Z"),
    )

    @Test
    fun `длинный раздел без вложенных заголовков делится на родителей в пределах размера, дети привязаны к своему родителю`() {
        val result = chunker(maxParentChars = 600).chunk(chronicle)

        assertTrue(result.parents.size > 3, "30 событий не помещаются в 600 знаков")
        assertTrue(result.parents.all { it.text.length <= 600 }, "каждый родитель в пределах размера")
        assertTrue(result.parents.all { it.headings["H2"] == "Хроника" }, "заголовок раздела у каждого окна")
        val parentIds = result.parents.map { it.id }.toSet()
        assertEquals(parentIds.size, result.parents.size, "идентификаторы родителей разные")
        assertTrue(result.children.all { it.parentId in parentIds }, "каждый ребёнок указывает на существующего родителя")
    }

    @Test
    fun `нулевой предел оставляет раздел целым`() {
        assertEquals(1, chunker(maxParentChars = 0).chunk(chronicle).parents.size)
    }

    @Test
    fun `тип страницы берётся из класса body и адреса`() {
        fun page(
            bodyClass: String,
            url: String,
        ) = chunker(maxParentChars = 0).chunk(
            chronicle.copy(url = url, body = chronicle.body.replace("<body>", "<body class=\"$bodyClass\">")),
        )

        assertEquals(PageType.LIST, page("path-taxonomy", "https://fpmi.bsu.by/announcement").pageType)
        assertEquals(PageType.NEWS, page("path-node page-node-type-article", "https://fpmi.bsu.by/node/1635").pageType)
        val history = page("path-node page-node-type-page", "https://fpmi.bsu.by/history-1997")
        assertEquals(PageType.HISTORY, history.pageType)
        assertEquals(1997, history.year)
    }

    private fun smallPage(body: String) = FetchedDocument(
        url = "https://fpmi.bsu.by/Admissions-campaign",
        mediaType = "text/html",
        body = "<html><head><title>Абитуриенту</title></head><body><div id=\"block-famcs-content\">$body</div></body></html>",
        fetchedAt = Instant.parse("2026-10-08T00:00:00Z"),
    )

    @Test
    fun `маленькая страница становится одним родителем, а дети помнят свой раздел`() {
        val page = smallPage("<h2>Сроки</h2><p>Документы принимаются до 17 июля.</p><h2>Документы</h2><p>Паспорт и аттестат.</p>")

        val result = chunker(maxParentChars = 1500, wholePageMaxChars = 4000).chunk(page)

        assertEquals(1, result.parents.size, "две секции слились в одного родителя")
        assertTrue(
            "## Сроки" in result.parents.single().text && "## Документы" in result.parents.single().text,
            "заголовки в тексте родителя",
        )
        assertEquals(setOf("Сроки", "Документы"), result.children.mapNotNull { it.headings["H2"] }.toSet(), "ребёнок помнит свой заголовок")
        assertTrue(result.children.all { it.parentId == result.parents.single().id })
    }

    @Test
    fun `большая страница по-прежнему делится на разделы`() {
        val page = smallPage("<h2>А</h2><p>${"слово ".repeat(500)}</p><h2>Б</h2><p>текст</p>")

        val result = chunker(maxParentChars = 1500, wholePageMaxChars = 1000).chunk(page)

        assertTrue(result.parents.size > 1)
    }

    @Test
    fun `таблица в странице превращается в строки с названиями колонок`() {
        val page = smallPage(
            "<h2>План приёма</h2><table><tr><th>Специальность</th><th>Бюджет</th></tr><tr><td>Информатика</td><td>20</td></tr></table>",
        )

        val result = chunker(maxParentChars = 0, tablesAsRows = true).chunk(page)

        assertTrue(
            result.children.any {
                "Специальность: Информатика; Бюджет: 20" in it.text
            },
            "строка таблицы самостоятельна: ${result.children.map { it.text }}",
        )
    }
}
