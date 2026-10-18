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
 * без параметров запроса (кроме номера страницы списка) и якорей. Улучшения: относительные ссылки разрешаются от адреса страницы, на которой найдены
 * (а не от корня сайта); страницы скачиваются один раз (раньше — дважды: при обходе и при индексации); соблюдается `robots.txt`.
 * Страницы, доступные по нескольким адресам, склеиваются по каноническому адресу (`<link rel="canonical">`): остаётся одна копия.
 * Отличие от Python: листание списков (`?page=N`) не отбрасывается. Иначе обход не доходит до новостей со второй страницы списка
 * и дальше (на сайте факультета так терялись 213 страниц). Сами страницы списков в документы не попадают: это перечни заголовков,
 * их текст повторяет заголовки новостей и засорил бы поиск.
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
        val identities = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(start to 0) }

        while (queue.isNotEmpty()) {
            val (url, depth) = queue.removeFirst()
            log.debug { "Обход: глубина $depth, $url" }
            when (val result = fetcher.fetch(url)) {
                is FetchResult.Success -> {
                    if (!result.document.mediaType.startsWith("text/html")) {
                        log.debug { "Пропущено (${result.document.mediaType}): $url" }
                    } else {
                        // Страница списка (`?page=N`) нужна только как источник ссылок на новости.
                        if (!isPaginationPage(url)) addDocument(result.document, host, identities)?.let { documents += it }
                        if (depth < settings.maxDepth) {
                            for (link in links(result.document)) {
                                if (link !in visited && isCrawlable(link, host, robots)) {
                                    visited += link
                                    // Переход на соседнюю страницу того же списка глубину не тратит: пейджер сайта даёт только ссылки
                                    // «назад» и «вперёд», и страница №10 иначе лежала бы на глубине 11 и не обходилась.
                                    queue.add(link to if (isSameListPage(url, link)) depth else depth + 1)
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

    /**
     * Определяет документ, который попадёт в базу: страница под своим или каноническим адресом либо `null`, если такая страница
     * уже есть (адрес-алиас). Канонический адрес принимается, только если он на том же сайте и после нормализации не пуст.
     *
     * @param document скачанная страница.
     * @param host домен сайта.
     * @param identities адреса страниц, уже взятых в базу; пополняется.
     */
    private fun addDocument(
        document: FetchedDocument,
        host: String,
        identities: MutableSet<String>,
    ): FetchedDocument? {
        val canonical = if (settings.useCanonicalUrl) canonicalOf(document, host) else null
        val identity = canonical ?: document.url
        if (!identities.add(identity)) {
            log.debug { "Пропущена копия (алиас): ${document.url} = $identity" }
            return null
        }
        return if (identity == document.url) document else document.copy(url = identity)
    }

    /** Канонический адрес из `<link rel="canonical">` в нормализованном виде; `null`, если его нет или он ведёт на другой сайт. */
    private fun canonicalOf(
        document: FetchedDocument,
        host: String,
    ): String? {
        val href = Jsoup
            .parse(document.body, document.url)
            .selectFirst("link[rel=canonical]")
            ?.absUrl("href")
            .orEmpty()
        val normalized = normalize(href) ?: return null
        return normalized.takeIf { URI(it).host.equals(host, ignoreCase = true) }
    }

    /** Ведёт ли ссылка на другую страницу того же списка: тот же путь, у ссылки есть номер страницы. */
    private fun isSameListPage(
        from: String,
        link: String,
    ): Boolean = isPaginationPage(link) && from.substringBefore('?') == link.substringBefore('?')

    /** Адрес страницы списка с номером страницы в параметре запроса, например `/announcement?page=3`. */
    private fun isPaginationPage(url: String): Boolean = settings.paginationParameter.isNotBlank() && '?' in url

    /**
     * Номер страницы списка из строки параметров запроса.
     *
     * @param query строка запроса без знака вопроса (`page=3&x=1`) или `null`.
     * @return положительный номер страницы либо `null`, если параметра нет, он не число или равен нулю (страница 0 — сам список).
     */
    private fun pageNumber(query: String?): Int? {
        if (settings.paginationParameter.isBlank() || query == null) return null
        val value = query.split('&').firstOrNull { it.substringBefore('=') == settings.paginationParameter }?.substringAfter('=', "")
        return value?.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * Оставляет схему, домен и путь; убирает якорь и завершающий слеш. Из параметров запроса оставляет только номер страницы
     * списка (если он задан в настройках и больше нуля), остальные отбрасываются.
     */
    internal fun normalize(raw: String): String? = try {
        val uri = URI(raw.trim())
        if (uri.scheme == null || uri.host == null) {
            null
        } else {
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val page = pageNumber(uri.rawQuery)?.let { "?${settings.paginationParameter}=$it" }.orEmpty()
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}${if (uri.port > 0) ":${uri.port}" else ""}$path$page"
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
