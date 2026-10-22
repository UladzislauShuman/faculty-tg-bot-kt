package shuman.bsu.famcs.rag.knowledge.chunking

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Проверка контекстной строки фрагмента: название страницы и заголовок раздела. */
class ChunkContextTest {
    @Test
    fun `название страницы без хвоста с названием сайта`() {
        assertEquals("История 1997", ChunkContext.shortTitle("История 1997 | Факультет прикладной математики и информатики БГУ"))
        assertEquals("Деканат", ChunkContext.shortTitle("Деканат"))
    }

    @Test
    fun `строка контекста включает заголовки раздела, но не повторяет название страницы`() {
        assertEquals(
            "Абитуриенту. Раздел: План приёма / Бюджет",
            ChunkContext.line("Абитуриенту | Факультет", mapOf("H2" to "План приёма", "H3" to "Бюджет")),
        )
        assertEquals(
            "Деканат",
            ChunkContext.line("Деканат | Факультет", mapOf("H2" to "Деканат")),
            "заголовок, равный названию, не повторяется",
        )
        assertEquals("Деканат", ChunkContext.line("Деканат", emptyMap()))
    }

    @Test
    fun `текст для поиска начинается с контекста, чистый текст остаётся в конце`() {
        assertEquals("Деканат. Раздел: Приёмная\nКабинет 5", ChunkContext.searchText("Деканат", mapOf("H2" to "Приёмная"), "Кабинет 5"))
    }
}
