package shuman.bsu.famcs.rag.knowledge.source

import shuman.bsu.famcs.rag.knowledge.FetchedDocument

/** Снимок источника: что получилось собрать за один обход. */
class SourceSnapshot(
    val documents: List<FetchedDocument>,
    /** Адреса, которые источник явно назвал несуществующими (404, 410). */
    val notFound: List<String>,
    /** Адреса, которые не удалось скачать из-за сбоя. Их прежние версии остаются в индексе. */
    val failed: List<String>,
)

/**
 * Источник знаний: сайт, таблица расписания, папка файлов.
 *
 * Новый тип источника подключается ещё одной реализацией; индексатор перебирает все источники и не знает, откуда документы.
 */
interface DataSource {
    /** Имя для логов и метрик. */
    val name: String

    /** Собирает все документы источника. */
    suspend fun snapshot(): SourceSnapshot

    /** Проверяет один адрес: нужно, чтобы убедиться, что пропавшая из обхода страница действительно удалена. */
    suspend fun fetch(url: String): FetchResult
}

/** Сайт факультета: обход по ссылкам с заданного адреса. */
class WebsiteDataSource(
    private val crawler: SiteCrawler,
    private val fetcher: PageFetcher,
) : DataSource {
    override val name = "website"

    override suspend fun snapshot(): SourceSnapshot {
        val result = crawler.crawl()
        return SourceSnapshot(result.documents, result.notFound, result.failed)
    }

    override suspend fun fetch(url: String): FetchResult = fetcher.fetch(url)
}
