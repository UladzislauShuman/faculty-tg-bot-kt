package shuman.bsu.famcs.rag.knowledge.model

import java.time.Instant

/** Скачанный документ до разбиения. */
data class FetchedDocument(
    /** Адрес документа. Служит идентификатором источника и ссылкой в ответе. */
    val url: String,
    /** Тип содержимого, например `text/html`. По нему выбирается разбиватель. */
    val mediaType: String,
    val body: String,
    val fetchedAt: Instant,
    /** Страница найдена перебором номеров, а не по ссылкам (на неё не ведёт ни одна ссылка сайта). */
    val orphan: Boolean = false,
)
