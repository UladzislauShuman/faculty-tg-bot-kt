package shuman.bsu.famcs.rag.knowledge.model

/** Снимок источника: что получилось собрать за один обход. */
class SourceSnapshot(
    val documents: List<FetchedDocument>,
    /** Адреса, которые источник явно назвал несуществующими (404, 410). */
    val notFound: List<String>,
    /** Адреса, которые не удалось скачать из-за сбоя. Их прежние версии остаются в индексе. */
    val failed: List<String>,
)
