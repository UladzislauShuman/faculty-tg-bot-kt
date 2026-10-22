package shuman.bsu.famcs.rag.knowledge.chunking.implementation

import shuman.bsu.famcs.rag.knowledge.model.PageType
import java.net.URI

/**
 * Определяет тип страницы и год по адресу, заголовку и классу `body` (CMS Drupal сайта факультета).
 *
 * Правила простые и проверяемые, без языковой модели: `path-frontpage` и `path-taxonomy` — перечни; `page-node-type-article` — новость;
 * адрес `history-ГГГГ` — хроника года; `kafedra-…` — кафедра; `catalog-of-academic-disciplines…` — каталог дисциплин;
 * год в конце адреса — мероприятие. Всё остальное — основной раздел. Год берётся из адреса, а если его там нет, из заголовка.
 */
object PageTypeResolver {
    private val HISTORY = Regex("^history-(\\d{4})$")
    private val YEAR = Regex("(?<!\\d)(19[6-9]\\d|20[0-3]\\d)(?!\\d)")

    /** Тип и год страницы. Год `null`, если определить его нельзя. */
    data class Result(
        val type: PageType,
        val year: Int?,
    )

    /**
     * Определяет тип и год.
     *
     * @param url адрес страницы.
     * @param bodyClass значение атрибута `class` тега `body`; пустая строка, если нет.
     * @param title заголовок страницы.
     */
    fun resolve(
        url: String,
        bodyClass: String,
        title: String,
    ): Result {
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        val classes = bodyClass.split(' ').toSet()
        val type = when {
            "path-frontpage" in classes || "path-taxonomy" in classes -> PageType.LIST
            HISTORY.matches(path) -> PageType.HISTORY
            "page-node-type-article" in classes -> PageType.NEWS
            path.startsWith("kafedra-") -> PageType.DEPARTMENT
            path.startsWith("catalog-of-academic-disciplines") -> PageType.CATALOG
            YEAR.containsMatchIn(path) -> PageType.EVENT
            else -> PageType.SECTION
        }
        val year = YEAR
            .findAll(path)
            .lastOrNull()
            ?.value
            ?.toInt() ?: YEAR.find(title)?.value?.toInt()
        return Result(type, year)
    }
}
