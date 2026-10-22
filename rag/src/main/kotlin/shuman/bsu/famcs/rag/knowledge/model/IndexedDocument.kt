package shuman.bsu.famcs.rag.knowledge.model

import java.time.Instant

/** Все фрагменты одной страницы. Запись заменяет прежнюю версию страницы целиком. */
class IndexedDocument(
    val url: String,
    val title: String,
    val indexedAt: Instant,
    /** Номер версии (запуска индексации). По нему удаляются фрагменты прежней версии той же страницы. */
    val version: Long,
    val chunks: List<IndexedChunk>,
    /** Тип страницы: хранится у каждого фрагмента и влияет на порядок выдачи. */
    val pageType: PageType = PageType.SECTION,
    /** Год страницы; `null`, если неизвестен. */
    val year: Int? = null,
    /** Оценка даты страницы: из имени картинки или текста (`DateExtractor`); `null`, если не найдена. */
    val published: java.time.LocalDate? = null,
    /** Страница-«сирота»: найдена перебором номеров, а не по ссылкам. Хранится у каждого фрагмента и понижает оценку. */
    val orphan: Boolean = false,
)
