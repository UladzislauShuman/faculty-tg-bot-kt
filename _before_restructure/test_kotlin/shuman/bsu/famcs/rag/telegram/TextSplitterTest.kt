package shuman.bsu.famcs.rag.telegram

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextSplitterTest {
    @Test
    fun `короткий текст остаётся одной частью`() {
        assertEquals(listOf("Привет!"), TextSplitter.split("Привет!", 100))
    }

    @Test
    fun `разрез идёт по границе абзаца, если она есть`() {
        val text = "Первый абзац из нескольких слов.\n\nВторой абзац тоже из нескольких слов."

        val parts = TextSplitter.split(text, 50)

        assertEquals(listOf("Первый абзац из нескольких слов.", "Второй абзац тоже из нескольких слов."), parts)
    }

    @Test
    fun `слова не рвутся посередине, части не длиннее лимита, текст сохраняется`() {
        val words = (1..40).map { "слово$it" }
        val text = words.joinToString(" ")

        val parts = TextSplitter.split(text, 50)

        assertTrue(parts.all { it.length <= 50 })
        assertEquals(words, parts.flatMap { it.split(" ") })
    }

    @Test
    fun `сплошная строка без границ режется по лимиту`() {
        val parts = TextSplitter.split("а".repeat(250), 100)

        assertEquals(listOf(100, 100, 50), parts.map { it.length })
    }

    @Test
    fun `пустой текст даёт пустой список, нулевой лимит отвергается`() {
        assertEquals(emptyList(), TextSplitter.split("   ", 10))
        assertFailsWith<IllegalArgumentException> { TextSplitter.split("текст", 0) }
    }
}
