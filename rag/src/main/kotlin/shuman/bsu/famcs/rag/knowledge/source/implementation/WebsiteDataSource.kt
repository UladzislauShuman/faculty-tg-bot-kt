package shuman.bsu.famcs.rag.knowledge.source.implementation

import shuman.bsu.famcs.rag.knowledge.model.FetchResult
import shuman.bsu.famcs.rag.knowledge.model.SourceSnapshot
import shuman.bsu.famcs.rag.knowledge.source.DataSource
import shuman.bsu.famcs.rag.knowledge.source.PageFetcher

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
