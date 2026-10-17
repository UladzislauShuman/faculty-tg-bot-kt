package shuman.bsu.famcs.rag.knowledge.indexing

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.HtmlToMarkdown
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.MarkdownHeaderSplitter
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.ParentChildHtmlChunker
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.RecursiveCharacterSplitter
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.model.IndexRunStatus
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.RussianTextAnalyzer
import shuman.bsu.famcs.rag.testsupport.FakeCollections
import shuman.bsu.famcs.rag.testsupport.HashEmbeddingProvider
import shuman.bsu.famcs.rag.testsupport.InMemoryIndexRunRepository
import shuman.bsu.famcs.rag.testsupport.InMemorySite
import shuman.bsu.famcs.rag.testsupport.InMemorySourceDocumentRepository
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.RecordingKnowledgeWriter
import shuman.bsu.famcs.rag.testsupport.StaticDataSource
import shuman.bsu.famcs.rag.testsupport.TestCrawler
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IndexerTest {
    private val urls = listOf("https://site.test/a", "https://site.test/b", "https://site.test/c")
    private val site = InMemorySite(urls.associateWith { TestCrawler.page("Раздел" to "Текст страницы $it") })
    private val writer = RecordingKnowledgeWriter()
    private val collections = FakeCollections(writer)
    private val documents = InMemorySourceDocumentRepository()
    private val runs = InMemoryIndexRunRepository()
    private val embeddings = HashEmbeddingProvider()
    private val activeUrls = urls.toMutableList()

    private val indexer = Indexer(
        sources = listOf(StaticDataSource(site) { activeUrls.toList() }),
        chunkers = listOf(
            ParentChildHtmlChunker(
                converter = HtmlToMarkdown(escapeAsterisks = true, escapeUnderscores = true, bullets = "*+-", absolutizeLinks = false),
                headerSplitter = MarkdownHeaderSplitter(listOf(MarkdownHeaderSplitter.Header("##", "H2"))),
                childSplitter = RecursiveCharacterSplitter(300, 50, listOf("\n\n", "\n", ".", " ", "")),
                contentSelectors = listOf("div#block-famcs-content"),
                removedTags = listOf("script"),
            ),
        ),
        embeddings = embeddings,
        sparse = Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
        writer = writer,
        collections = collections,
        documents = documents,
        runs = runs,
        settings = TestCrawler.indexing,
        clock = MutableClock(),
        meters = SimpleMeterRegistry(),
    )

    @Test
    fun `первый запуск создаёт активную коллекцию и записывает все страницы`() = runTest {
        val report = indexer.run(IndexMode.INCREMENTAL)

        assertEquals(IndexRunStatus.SUCCEEDED, report.status)
        assertEquals(3, report.counters.changed)
        assertEquals("collection-1", collections.activeCollection)
        assertEquals(urls.toSet(), writer.documents.getValue("collection-1").keys)
        assertEquals(urls.toSet(), documents.rows.keys)
        assertEquals(
            report.runId,
            writer.documents
                .getValue("collection-1")
                .getValue(urls[0])
                .version,
            "версия данных равна номеру запуска",
        )
    }

    @Test
    fun `повторный запуск без изменений ничего не переписывает и не считает эмбеддинги`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        val callsAfterFirst = embeddings.documentCalls

        val report = indexer.run(IndexMode.INCREMENTAL)

        assertEquals(0, report.counters.changed)
        assertEquals(callsAfterFirst, embeddings.documentCalls, "эмбеддинги не пересчитывались")
        assertEquals(1, documents.rows.getValue(urls[0]).version, "версия страницы осталась прежней")
    }

    @Test
    fun `изменившаяся страница переписывается с новой версией, остальные остаются`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        site.set(urls[1], TestCrawler.page("Раздел" to "Совсем другой текст"))

        val report = indexer.run(IndexMode.INCREMENTAL)

        assertEquals(1, report.counters.changed)
        assertEquals(report.runId, documents.rows.getValue(urls[1]).version)
        assertEquals(1, documents.rows.getValue(urls[0]).version)
        val stored = writer.documents.getValue("collection-1").getValue(urls[1])
        assertTrue(
            stored.chunks
                .single()
                .text
                .contains("Совсем другой"),
        )
    }

    @Test
    fun `страница, которую сайт назвал несуществующей, удаляется из индекса`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        site.remove(urls[2])

        val report = indexer.run(IndexMode.INCREMENTAL)

        assertEquals(1, report.counters.removed)
        assertTrue(urls[2] !in documents.rows)
        assertTrue(urls[2] !in writer.documents.getValue("collection-1"))
    }

    @Test
    fun `страница, пропавшая из обхода, но живая при проверке, остаётся в индексе`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        activeUrls.remove(urls[2]) // ссылку на страницу убрали, а сама страница ответит 200

        val report = indexer.run(IndexMode.INCREMENTAL)

        assertEquals(0, report.counters.removed)
        assertNotNull(documents.rows[urls[2]])
    }

    @Test
    fun `если обход вернул слишком мало страниц, обновление не применяется`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        activeUrls.retainAll(listOf(urls[0])) // 1 из 3 — меньше половины от прошлого раза

        val report = indexer.run(IndexMode.INCREMENTAL)

        assertEquals(IndexRunStatus.ABORTED, report.status)
        assertEquals(urls.toSet(), documents.rows.keys, "прежние данные не тронуты")
        assertEquals(0, writer.deleted.size)
    }

    @Test
    fun `если слишком много страниц не скачалось, обновление не применяется`() = runTest {
        val flaky = InMemorySite(urls.associateWith { TestCrawler.page("Раздел" to "Текст") }, failing = setOf(urls[0], urls[1]))
        val flakyIndexer = Indexer(
            listOf(StaticDataSource(flaky) { urls }),
            emptyList(),
            embeddings,
            Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
            writer,
            collections,
            documents,
            runs,
            TestCrawler.indexing,
            MutableClock(),
            SimpleMeterRegistry(),
        )

        val report = flakyIndexer.run(IndexMode.INCREMENTAL)

        assertEquals(IndexRunStatus.ABORTED, report.status)
        assertTrue(writer.documents.isEmpty())
    }

    @Test
    fun `полная пересборка пишет в новую коллекцию и переключает на неё алиас в конце`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)

        val report = indexer.run(IndexMode.FULL)

        assertEquals(IndexRunStatus.SUCCEEDED, report.status)
        assertEquals("collection-2", collections.activeCollection)
        assertEquals(urls.toSet(), writer.documents.getValue("collection-2").keys, "все страницы переписаны, хеши не пропускают запись")
        assertEquals(1, collections.dropCalls)
    }

    @Test
    fun `полная пересборка с ошибками не переключает алиас`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        val broken = InMemorySite(urls.associateWith { TestCrawler.page("Раздел" to "Текст") }, failing = setOf(urls[0]))
        val fullIndexer = Indexer(
            listOf(
                StaticDataSource(broken) {
                    urls
                },
            ),
            listOf(chunkerForBroken()),
            embeddings,
            Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
            writer,
            collections,
            documents,
            runs,
            TestCrawler.indexing.copy(maxFailedShare = 0.5),
            MutableClock(),
            SimpleMeterRegistry(),
        )

        val report = fullIndexer.run(IndexMode.FULL)

        assertEquals(IndexRunStatus.ABORTED, report.status)
        assertEquals("collection-1", collections.activeCollection, "поиск остался на прежней коллекции")
    }

    @Test
    fun `полная пересборка с пропавшей из обхода живой страницей отменяется и ничего не меняет в учёте`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        val oldHash = documents.rows.getValue(urls[1]).contentHash
        site.set(urls[1], TestCrawler.page("Раздел" to "Совсем новый текст"))
        // Страница c жива на сайте, но ссылку на неё в этот раз не нашли: подтвердить удаление нельзя.
        activeUrls.remove(urls[2])

        val report = indexer.run(IndexMode.FULL)

        assertEquals(IndexRunStatus.ABORTED, report.status)
        assertEquals("collection-1", collections.activeCollection, "алиас остался на прежней коллекции")
        assertEquals(oldHash, documents.rows.getValue(urls[1]).contentHash, "учёт страниц не тронут: в рабочей коллекции прежний текст")
        assertEquals(setOf(urls[2]), setOf(urls[2]).intersect(documents.rows.keys), "страница не забыта")
    }

    @Test
    fun `после отменённой пересборки обычное обновление всё ещё видит изменившуюся страницу`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        site.set(urls[1], TestCrawler.page("Раздел" to "Совсем новый текст"))
        activeUrls.remove(urls[2])
        indexer.run(IndexMode.FULL)
        activeUrls.add(urls[2])

        val next = indexer.run(IndexMode.INCREMENTAL)

        assertEquals(1, next.counters.changed, "изменившаяся страница b переписана в рабочую коллекцию")
        assertTrue(
            "новый" in writer.documents
                .getValue("collection-1")
                .getValue(urls[1])
                .chunks
                .first()
                .parentText,
        )
    }

    @Test
    fun `отменённая пересборка удаляет свою незавершённую коллекцию`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        activeUrls.remove(urls[2])

        indexer.run(IndexMode.FULL)

        assertEquals(listOf("collection-2"), collections.discarded)
        assertEquals(0, collections.dropCalls, "слот отката не потрачен")
    }

    @Test
    fun `успешная пересборка обновляет учёт страниц только после переключения алиаса`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        site.set(urls[0], TestCrawler.page("Раздел" to "Обновлённый текст страницы a"))

        val report = indexer.run(IndexMode.FULL)

        assertEquals(IndexRunStatus.SUCCEEDED, report.status)
        assertEquals(report.runId, documents.rows.getValue(urls[0]).version)
        assertEquals("collection-2", collections.activeCollection)
    }

    @Test
    fun `полная пересборка при резком падении числа страниц не применяется`() = runTest {
        indexer.run(IndexMode.INCREMENTAL)
        activeUrls.removeAll(urls.drop(1))

        val report = indexer.run(IndexMode.FULL)

        assertEquals(IndexRunStatus.ABORTED, report.status)
        assertTrue("прошлый раз" in report.message.orEmpty())
        assertEquals(emptyList(), collections.created.drop(1), "новая коллекция даже не создавалась")
    }

    private fun chunkerForBroken() = ParentChildHtmlChunker(
        converter = HtmlToMarkdown(true, true, "*+-", false),
        headerSplitter = MarkdownHeaderSplitter(listOf(MarkdownHeaderSplitter.Header("##", "H2"))),
        childSplitter = RecursiveCharacterSplitter(300, 50, listOf("\n\n", "\n", ".", " ", "")),
        contentSelectors = listOf("div#block-famcs-content"),
        removedTags = emptyList(),
    )
}
