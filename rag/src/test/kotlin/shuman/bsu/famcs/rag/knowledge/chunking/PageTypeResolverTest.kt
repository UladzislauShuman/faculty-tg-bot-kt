package shuman.bsu.famcs.rag.knowledge.chunking

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.PageTypeResolver
import shuman.bsu.famcs.rag.knowledge.model.PageType
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Проверка определения типа и года страницы по правилам сайта на Drupal. */
class PageTypeResolverTest {
    private val host = "https://fpmi.bsu.by"

    private fun resolve(
        path: String,
        bodyClass: String = "path-node page-node-type-page",
        title: String = "Страница",
    ) = PageTypeResolver.resolve("$host$path", bodyClass, title)

    @Test
    fun `главная и списки по теме это перечни`() {
        assertEquals(PageType.LIST, resolve("/", bodyClass = "path-frontpage").type)
        assertEquals(PageType.LIST, resolve("/announcement", bodyClass = "path-taxonomy").type)
        assertEquals(PageType.LIST, resolve("/taxonomy/term/6", bodyClass = "path-taxonomy").type)
    }

    @Test
    fun `новость определяется по классу узла`() {
        assertEquals(PageType.NEWS, resolve("/node/1635", bodyClass = "path-node page-node-type-article").type)
    }

    @Test
    fun `хроника года берёт год из адреса, кафедра и каталог распознаются по началу адреса`() {
        val history = resolve("/history-1997", title = "История 1997 | Факультет")
        assertEquals(PageType.HISTORY, history.type)
        assertEquals(1997, history.year)
        assertEquals(PageType.DEPARTMENT, resolve("/kafedra-DMA").type)
        assertEquals(PageType.CATALOG, resolve("/catalog-of-academic-disciplines/6-05-0533-10-informatics").type)
    }

    @Test
    fun `мероприятие определяется по году в адресе, основной раздел остаётся основным`() {
        val event = resolve("/csist-2025")
        assertEquals(PageType.EVENT, event.type)
        assertEquals(2025, event.year)
        val dean = resolve("/dean-office")
        assertEquals(PageType.SECTION, dean.type)
        assertNull(dean.year)
    }

    @Test
    fun `год берётся из заголовка, если его нет в адресе`() {
        assertEquals(2022, resolve("/node/1611", title = "Декан с 4 ноября 2022 года").year)
    }
}
