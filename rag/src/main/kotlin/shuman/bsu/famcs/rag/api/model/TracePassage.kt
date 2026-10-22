package shuman.bsu.famcs.rag.api.model

/** Раздел базы знаний, который попал в промпт модели. Нужен для проверки поиска. */
data class TracePassage(
    val url: String,
    val title: String,
    /** Идентификатор раздела. Меняется, если текст страницы изменился, поэтому для эталона не годится: эталон хранится по адресу. */
    val parentId: String,
    /** Оценка реранкера от 0 до 1. `null`, если реранкер выключен. */
    val relevance: Double?,
    val text: String,
)
