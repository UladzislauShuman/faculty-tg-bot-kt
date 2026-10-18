package shuman.bsu.famcs.rag.knowledge.chunking

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.ParentWindowSplitter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверка деления длинных родительских разделов на окна. */
class ParentWindowSplitterTest {
    private val paragraph = "Абзац из ста символов. ".repeat(4).trim() // 91 знак

    @Test
    fun `короткий раздел и отключённое ограничение не делятся`() {
        assertEquals(listOf(paragraph), ParentWindowSplitter.split(paragraph, 500))
        assertEquals(1, ParentWindowSplitter.split(paragraph.repeat(50), 0).size)
    }

    @Test
    fun `длинный раздел режется по границам абзацев, окна не длиннее предела, текст не теряется`() {
        val text = List(40) { "$paragraph $it" }.joinToString("\n\n")

        val windows = ParentWindowSplitter.split(text, 1000)

        assertTrue(windows.size > 3, "40 абзацев по ~95 знаков не помещаются в одно окно")
        assertTrue(windows.all { it.length <= 1000 }, "каждое окно в пределах размера")
        assertTrue(windows.all { it.endsWith(Regex("\\d+").find(it.takeLast(5))!!.value) }, "окно заканчивается целым абзацем")
        assertEquals(text.replace(Regex("\\s+"), ""), windows.joinToString("").replace(Regex("\\s+"), ""), "содержимое сохранено")
    }

    @Test
    fun `текст без абзацев режется по длине, а короткий хвост присоединяется к предыдущему окну`() {
        val text = "я".repeat(1050)

        val windows = ParentWindowSplitter.split(text, 1000)

        assertEquals(1, windows.size, "хвост из 50 знаков присоединён")
        assertEquals(1050, windows.single().length)
    }
}
