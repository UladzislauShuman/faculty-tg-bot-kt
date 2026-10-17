package shuman.bsu.famcs.rag.knowledge.source

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.testsupport.InMemorySite
import shuman.bsu.famcs.rag.testsupport.TestCrawler
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiteCrawlerTest {
    private fun crawl(
        site: InMemorySite,
        maxDepth: Int = 5,
        respectRobots: Boolean = true,
    ) = SiteCrawler(site, TestCrawler.settings(maxDepth = maxDepth, respectRobots = respectRobots))

    @Test
    fun `обход идёт по ссылкам одного домена и скачивает каждую страницу один раз`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to TestCrawler.page("Главная" to "текст", links = listOf("/about", "/about", "https://other.test/x")),
                "https://site.test/about" to TestCrawler.page("О нас" to "текст", links = listOf("/", "/news")),
                "https://site.test/news" to TestCrawler.page("Новости" to "текст"),
            ),
        )

        val result = crawl(site).crawl()

        assertEquals(
            setOf("https://site.test", "https://site.test/about", "https://site.test/news"),
            result.documents.map { it.url }.toSet(),
        )
        assertEquals(1, site.requested.count { it == "https://site.test/about" }, "страница скачана один раз")
        assertTrue("https://other.test/x" !in site.requested, "чужой домен не обходится")
    }

    @Test
    fun `параметры запроса и якоря отбрасываются, слеш в конце не создаёт дубль`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to TestCrawler.page("Главная" to "текст", links = listOf("/news?page=2", "/news/", "/news#top")),
                "https://site.test/news" to TestCrawler.page("Новости" to "текст"),
            ),
        )

        val result = crawl(site).crawl()

        assertEquals(1, site.requested.count { it == "https://site.test/news" })
        assertEquals(2, result.documents.size)
    }

    @Test
    fun `относительные ссылки разрешаются от адреса страницы, а не от корня сайта`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to TestCrawler.page("Главная" to "текст", links = listOf("/about/team")),
                "https://site.test/about/team" to TestCrawler.page("Команда" to "текст", links = listOf("../contacts")),
                "https://site.test/contacts" to TestCrawler.page("Контакты" to "текст"),
            ),
        )

        val urls = crawl(site).crawl().documents.map { it.url }

        assertTrue("https://site.test/contacts" in urls, "«../contacts» от /about/team — это /contacts; найдено: $urls")
    }

    @Test
    fun `файлы и служебные разделы пропускаются`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to TestCrawler.page(
                    "Главная" to "текст",
                    links = listOf("/docs/plan.pdf", "/img/a.jpg", "/admin/panel", "/user/login", "/ok"),
                ),
                "https://site.test/ok" to TestCrawler.page("ОК" to "текст"),
            ),
        )

        val result = crawl(site).crawl()

        assertEquals(setOf("https://site.test", "https://site.test/ok"), result.documents.map { it.url }.toSet())
        assertEquals(emptyList(), site.requested.filter { "pdf" in it || "admin" in it || "user" in it || ".jpg" in it })
    }

    @Test
    fun `глубина обхода ограничена`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to TestCrawler.page("0" to "текст", links = listOf("/a")),
                "https://site.test/a" to TestCrawler.page("1" to "текст", links = listOf("/b")),
                "https://site.test/b" to TestCrawler.page("2" to "текст"),
            ),
        )

        val urls = crawl(site, maxDepth = 1).crawl().documents.map { it.url }

        assertEquals(setOf("https://site.test", "https://site.test/a"), urls.toSet())
    }

    @Test
    fun `страницы с ошибкой и 404 попадают в отдельные списки и не прерывают обход`() = runTest {
        val site = InMemorySite(
            pages = mapOf(
                "https://site.test" to TestCrawler.page("Главная" to "текст", links = listOf("/broken", "/missing", "/fine")),
                "https://site.test/fine" to TestCrawler.page("ОК" to "текст"),
            ),
            failing = setOf("https://site.test/broken"),
        )

        val result = crawl(site).crawl()

        assertEquals(listOf("https://site.test/broken"), result.failed)
        assertEquals(listOf("https://site.test/missing"), result.notFound)
        assertEquals(2, result.documents.size)
    }

    @Test
    fun `robots txt запрещает разделы, разрешение с более длинным префиксом сильнее запрета`() = runTest {
        val rules = RobotsRules.parse(
            """
            User-agent: *
            Disallow: /private
            Allow: /private/open
            Disallow: /tmp # комментарий
            """.trimIndent(),
            userAgent = "FpmiBot/1.0",
        )

        assertTrue(!rules.isAllowed("/private/x"))
        assertTrue(rules.isAllowed("/private/open/page"))
        assertTrue(!rules.isAllowed("/tmp"))
        assertTrue(rules.isAllowed("/news"))
    }

    @Test
    fun `группа robots txt для другого робота не применяется`() {
        val rules = RobotsRules.parse("User-agent: BadBot\nDisallow: /\n\nUser-agent: *\nDisallow: /secret", userAgent = "FpmiBot/1.0")

        assertTrue(rules.isAllowed("/news"))
        assertTrue(!rules.isAllowed("/secret/a"))
    }
}
