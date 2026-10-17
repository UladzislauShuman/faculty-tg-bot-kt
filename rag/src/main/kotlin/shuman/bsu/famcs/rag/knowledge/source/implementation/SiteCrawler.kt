package shuman.bsu.famcs.rag.knowledge.source.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.model.CrawlResult
import shuman.bsu.famcs.rag.knowledge.model.FetchResult
import shuman.bsu.famcs.rag.knowledge.model.FetchedDocument
import shuman.bsu.famcs.rag.knowledge.source.PageFetcher
import java.net.URI
import java.net.URISyntaxException

private val log = KotlinLogging.logger {}

/**
 * Обходит сайт по ссылкам в ширину в пределах одного домена и скачивает каждую страницу один раз.
 *
 * Правила взяты у Python-обходчика: тот же домен, без файлов (pdf, картинки, архивы) и служебных разделов (`/admin`, `/user`),
 * без параметров запроса и якорей. Улучшения: относительные ссылки разрешаются от адреса страницы, на которой найдены
 * (а не от корня сайта); страницы скачиваются один раз (раньше — дважды: при обходе и при индексации); соблюдается `robots.txt`.
 */
class SiteCrawler(
    private val fetcher: PageFetcher,
    private val settings: KnowledgeProperties.Crawler,
) {
    /**
     * Обходит сайт в ширину от стартового адреса: только свой домен, не глубже `maxDepth`,
     * с учётом `robots.txt` и исключённых расширений.
     *
     * @return найденные документы, адреса «не найдено» и адреса, которые не удалось скачать.
     */
    suspend fun crawl(): CrawlResult {
        val start = normalize(settings.startUrl) ?: error("Неверный адрес начала обхода: ${settings.startUrl}")
        val host = URI(start).host
        val robots = loadRobots(start)

        val documents = mutableListOf<FetchedDocument>()
        val notFound = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val visited = mutableSetOf(start)
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(start to 0) }

        while (queue.isNotEmpty()) {
            val (url, depth) = queue.removeFirst()
            log.debug { "Обход: глубина $depth, $url" }
            when (val result = fetcher.fetch(url)) {
                is FetchResult.Success -> {
                    if (!result.document.mediaType.startsWith("text/html")) {
                        log.debug { "Пропущено (${result.document.mediaType}): $url" }
                    } else {
                        documents += result.document
                        if (depth < settings.maxDepth) {
                            for (link in links(result.document)) {
                                if (link !in visited && isCrawlable(link, host, robots)) {
                                    visited += link
                                    queue.add(link to depth + 1)
                                }
                            }
                        }
                    }
                }

                is FetchResult.NotFound -> {
                    notFound += url
                }

                is FetchResult.Unsupported -> {
                    log.debug { "Пропущено (${result.mediaType}): $url" }
                }

                is FetchResult.Failed -> {
                    failed += url
                    log.warn { "Не удалось скачать $url: ${result.reason}" }
                }
            }
        }
        log.info { "Обход завершён: страниц ${documents.size}, не найдено ${notFound.size}, ошибок ${failed.size}" }
        return CrawlResult(documents, notFound, failed)
    }

    /**
     * Собирает ссылки страницы в нормализованном виде без повторов.
     *
     * @param document скачанная страница.
     */
    private fun links(document: FetchedDocument): List<String> =
        Jsoup
            .parse(document.body, document.url)
            .select("a[href]")
            .mapNotNull { normalize(it.absUrl("href")) }
            .distinct()

    /** Оставляет схему, домен и путь; убирает параметры запроса, якорь и завершающий слеш. */
    internal fun normalize(raw: String): String? = try {
        val uri = URI(raw.trim())
        if (uri.scheme == null || uri.host == null) {
            null
        } else {
            val path = uri.rawPath.orEmpty().trimEnd('/')
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}${if (uri.port > 0) ":${uri.port}" else ""}$path"
        }
    } catch (_: URISyntaxException) {
        null
    }

    /**
     * Можно ли идти по ссылке: схема http(s), тот же домен, расширение не из исключённых, путь разрешён `robots.txt`.
     *
     * @param url ссылка.
     * @param host домен сайта.
     * @param robots правила `robots.txt`.
     */
    private fun isCrawlable(
        url: String,
        host: String,
        robots: RobotsRules,
    ): Boolean {
        val uri = URI(url)
        if (uri.scheme != "http" && uri.scheme != "https") return false
        if (!uri.host.equals(host, ignoreCase = true)) return false
        val path = uri.rawPath.orEmpty()
        if (settings.excludedExtensions.any { path.endsWith(it, ignoreCase = true) }) return false
        if (settings.excludedPathParts.any { path.contains(it) }) return false
        return !settings.respectRobotsTxt || robots.isAllowed(path.ifEmpty { "/" })
    }

    /**
     * Скачивает и разбирает `robots.txt`; без файла (или при выключенной настройке) разрешено всё.
     *
     * @param start стартовый адрес, по нему определяется домен.
     */
    private suspend fun loadRobots(start: String): RobotsRules {
        if (!settings.respectRobotsTxt) return RobotsRules.ALLOW_ALL
        val uri = URI(start)
        val robotsUrl = "${uri.scheme}://${uri.authority}/robots.txt"
        return when (val result = fetcher.fetch(robotsUrl)) {
            is FetchResult.Success -> {
                RobotsRules.parse(result.document.body, settings.userAgent)
            }

            else -> {
                log.info { "robots.txt недоступен ($robotsUrl): ограничений нет" }
                RobotsRules.ALLOW_ALL
            }
        }
    }
}
