package shuman.bsu.famcs.rag.knowledge.source

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.source.implementation.RobotsRules
import shuman.bsu.famcs.rag.knowledge.source.implementation.SiteCrawler
import shuman.bsu.famcs.rag.testsupport.InMemorySite
import shuman.bsu.famcs.rag.testsupport.TestCrawler
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiteCrawlerTest {
    private fun crawl(
        site: InMemorySite,
        maxDepth: Int = 5,
        respectRobots: Boolean = true,
        paginationParameter: String = "",
        useCanonicalUrl: Boolean = false,
    ) = SiteCrawler(
        site,
        TestCrawler.settings(
            maxDepth = maxDepth,
            respectRobots = respectRobots,
            paginationParameter = paginationParameter,
            useCanonicalUrl = useCanonicalUrl,
        ),
    )

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
    fun `листание списков доходит до новостей на следующих страницах, но сами списки в документы не попадают`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to TestCrawler.page("Главная" to "текст", links = listOf("/news")),
                "https://site.test/news" to
                    TestCrawler.page("Новости" to "список", links = listOf("/item1", "/news?page=1", "/news?page=0&x=1")),
                "https://site.test/news?page=1" to TestCrawler.page(
                    "Новости 2" to "список",
                    links = listOf("/item2", "/news?page=2&utm=a"),
                ),
                "https://site.test/news?page=2" to TestCrawler.page("Новости 3" to "список", links = listOf("/item3")),
                "https://site.test/item1" to TestCrawler.page("Новость 1" to "текст"),
                "https://site.test/item2" to TestCrawler.page("Новость 2" to "текст"),
                "https://site.test/item3" to TestCrawler.page("Новость 3" to "текст"),
            ),
        )

        val result = crawl(site, paginationParameter = "page").crawl()

        assertEquals(
            setOf(
                "https://site.test",
                "https://site.test/news",
                "https://site.test/item1",
                "https://site.test/item2",
                "https://site.test/item3",
            ),
            result.documents.map { it.url }.toSet(),
            "новости со 2-й и 3-й страницы списка найдены, страницы списков ?page=N не сохранены",
        )
        assertEquals(1, site.requested.count { it == "https://site.test/news" }, "?page=0 и прочие параметры не создают дубль списка")
    }

    @Test
    fun `листание длинного списка не упирается в лимит глубины`() = runTest {
        // Пейджер даёт только «вперёд» и «назад», как у сайта факультета: страница 8 лежала бы на глубине 9.
        val pages = mutableMapOf(
            "https://site.test" to TestCrawler.page("Главная" to "текст", links = listOf("/news")),
            "https://site.test/news" to TestCrawler.page("Новости" to "список", links = listOf("/news?page=1")),
            "https://site.test/last" to TestCrawler.page("Последняя новость" to "текст"),
        )
        for (n in 1..8) {
            val next = if (n < 8) "/news?page=${n + 1}" else "/last"
            pages["https://site.test/news?page=$n"] = TestCrawler.page("Список $n" to "список", links = listOf("/news?page=${n - 1}", next))
        }

        val urls = crawl(InMemorySite(pages), maxDepth = 3, paginationParameter = "page").crawl().documents.map { it.url }

        assertTrue("https://site.test/last" in urls, "новость с 8-й страницы списка найдена при глубине 3")
    }

    @Test
    fun `страницы с одним каноническим адресом склеиваются в одну под каноническим адресом`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to
                    TestCrawler.page("Главная" to "текст", links = listOf("/enrollee", "/Admissions-campaign", "/other", "/foreign")),
                "https://site.test/enrollee" to
                    TestCrawler.page("Абитуриенту" to "текст", canonical = "https://site.test/Admissions-campaign"),
                "https://site.test/Admissions-campaign" to
                    TestCrawler.page("Абитуриенту" to "текст", canonical = "https://site.test/Admissions-campaign"),
                "https://site.test/other" to TestCrawler.page("Другая" to "текст"),
                "https://site.test/foreign" to TestCrawler.page("Чужой канонический" to "текст", canonical = "https://elsewhere.test/page"),
            ),
        )

        val urls = crawl(site, useCanonicalUrl = true).crawl().documents.map { it.url }

        assertEquals(1, urls.count { it == "https://site.test/Admissions-campaign" }, "две копии стали одной")
        assertTrue("https://site.test/enrollee" !in urls, "адрес-алиас в базу не идёт")
        assertTrue(
            "https://site.test/other" in urls && "https://site.test/foreign" in urls,
            "страница без канонического или с чужим остаётся под своим адресом",
        )
        assertEquals(4, urls.size)
    }

    @Test
    fun `без настройки канонические адреса не используются`() = runTest {
        val site = InMemorySite(
            mapOf(
                "https://site.test" to TestCrawler.page("Главная" to "текст", links = listOf("/a", "/b")),
                "https://site.test/a" to TestCrawler.page("А" to "текст", canonical = "https://site.test/b"),
                "https://site.test/b" to TestCrawler.page("Б" to "текст"),
            ),
        )

        assertEquals(3, crawl(site).crawl().documents.size)
    }

    @Test
    fun `нормализация оставляет только номер страницы списка и только если он больше нуля`() {
        val crawler = SiteCrawler(InMemorySite(emptyMap()), TestCrawler.settings(paginationParameter = "page"))

        assertEquals("https://site.test/news?page=3", crawler.normalize("https://site.test/news/?x=1&page=3#top"))
        assertEquals("https://site.test/news", crawler.normalize("https://site.test/news?page=0"))
        assertEquals("https://site.test/news", crawler.normalize("https://site.test/news?page=abc"))
        assertEquals("https://site.test/news", crawler.normalize("https://site.test/news?other=2"))
        assertEquals(
            "https://site.test/news",
            SiteCrawler(InMemorySite(emptyMap()), TestCrawler.settings()).normalize("https://site.test/news?page=3"),
        )
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
