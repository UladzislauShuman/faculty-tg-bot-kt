package shuman.bsu.famcs.rag.knowledge.model

import java.time.Instant

/** Раздел документа, найденный по вопросу: этот текст уходит в промпт как контекст. */
data class RetrievedPassage(
    val parentId: String,
    val text: String,
    val url: String,
    val title: String,
    val headings: Map<String, String>,
    /** Когда страница в последний раз проверялась индексатором. */
    val indexedAt: Instant?,
    /** Оценка реранкера от 0 до 1. `null`, если реранкер выключен. */
    val relevance: Double?,
    val pageType: PageType = PageType.SECTION,
    /** Год страницы; попадает в подпись раздела в промпте. */
    val year: Int? = null,
    /** Оценка даты страницы; попадает в подпись раздела в промпте. */
    val published: java.time.LocalDate? = null,
)
