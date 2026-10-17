package shuman.bsu.famcs.rag.knowledge.model

/** Результат обхода: страницы, которые скачались, и адреса, которые не удалось получить. */
class CrawlResult(
    val documents: List<FetchedDocument>,
    val notFound: List<String>,
    val failed: List<String>,
)
