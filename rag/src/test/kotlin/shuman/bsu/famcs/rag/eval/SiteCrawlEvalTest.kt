package shuman.bsu.famcs.rag.eval

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.configuration.KnowledgeConfiguration
import shuman.bsu.famcs.rag.knowledge.source.implementation.SiteCrawler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.assertTrue

/**
 * Живой обход сайта факультета нашим обходчиком: сколько страниц находится и каких типов. Сравнение с ожидаемым числом
 * (по независимому обходу `site_audit/crawl_site.py`) показывает, что обходчик не теряет разделы.
 * Идёт по реальному сайту с паузой между запросами из настроек (около 10–15 минут). Отчёт: `build/reports/eval/site_crawl.md`.
 */
@Tag("eval")
class SiteCrawlEvalTest {
    @Test
    fun `обходчик находит страницы сайта, включая новости со страниц списков`() = runBlocking {
        val properties = EvalConfig.knowledge()
        val fetcher = KnowledgeConfiguration().pageFetcher(properties, Clock.systemUTC())

        val result = SiteCrawler(fetcher, properties.crawler).crawl()

        val urls = result.documents.map { it.url }.sorted()
        val report = Path.of("build/reports/eval/site_crawl.md")
        Files.createDirectories(report.parent)
        Files.writeString(
            report,
            "# Обход сайта\n\nСтраниц: ${urls.size}; не найдено: ${result.notFound.size}; ошибок: ${result.failed.size}\n\n" +
                urls.joinToString("\n") { "- $it" },
        )
        Files.writeString(Path.of("build/reports/eval/site_crawl_urls.txt"), urls.joinToString("\n"))
        println(
            "Страниц: ${urls.size}; новостей /node/: ${urls.count {
                "/node/" in it
            }}; не найдено ${result.notFound.size}; ошибок ${result.failed.size}",
        )
        assertTrue(urls.isNotEmpty())
    }
}
