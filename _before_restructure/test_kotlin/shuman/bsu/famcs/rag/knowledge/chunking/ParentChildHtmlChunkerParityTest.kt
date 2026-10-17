package shuman.bsu.famcs.rag.knowledge.chunking

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.FetchedDocument
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
