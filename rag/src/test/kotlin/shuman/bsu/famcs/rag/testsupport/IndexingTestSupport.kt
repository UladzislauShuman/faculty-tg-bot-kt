package shuman.bsu.famcs.rag.testsupport

import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.indexing.repository.IndexRunRepository
import shuman.bsu.famcs.rag.knowledge.indexing.repository.SourceDocumentRepository
import shuman.bsu.famcs.rag.knowledge.model.FetchResult
import shuman.bsu.famcs.rag.knowledge.model.FetchedDocument
import shuman.bsu.famcs.rag.knowledge.model.IndexCounters
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.model.IndexRunStatus
import shuman.bsu.famcs.rag.knowledge.model.IndexedDocument
import shuman.bsu.famcs.rag.knowledge.model.KnownDocument
import shuman.bsu.famcs.rag.knowledge.model.SourceSnapshot
import shuman.bsu.famcs.rag.knowledge.source.DataSource
import shuman.bsu.famcs.rag.knowledge.source.PageFetcher
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseWriter
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeCollections
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Сайт в памяти: адрес → страница. Неизвестные адреса отвечают 404. Запросы записываются. */
class InMemorySite(
    pages: Map<String, String>,
    private val failing: Set<String> = emptySet(),
) : PageFetcher {
    private val pages = pages.toMutableMap()
    val requested = mutableListOf<String>()

    fun set(
        url: String,
        html: String,
    ) {
        pages[url] = html
    }

    fun remove(url: String) {
        pages.remove(url)
    }

    override suspend fun fetch(url: String): FetchResult {
        requested += url
        return when {
            url in failing -> FetchResult.Failed(url, "сбой сети")

            url in pages -> FetchResult.Success(
                FetchedDocument(url, "text/html", pages.getValue(url), Instant.parse("2026-10-08T03:00:00Z")),
            )

            else -> FetchResult.NotFound(url, 404)
        }
    }
}

/** Источник на основе готового снимка. */
class StaticDataSource(
    private val site: InMemorySite,
    private val urls: () -> List<String>,
) : DataSource {
    override val name = "static"

    override suspend fun snapshot(): SourceSnapshot {
        val results = urls().map { site.fetch(it) }
        return SourceSnapshot(
            documents = results.filterIsInstance<FetchResult.Success>().map { it.document },
            notFound = results.filterIsInstance<FetchResult.NotFound>().map { it.url },
            failed = results.filterIsInstance<FetchResult.Failed>().map { it.url },
        )
    }

    override suspend fun fetch(url: String): FetchResult = site.fetch(url)
}

/** Эмбеддинги без модели: вектор получается из хеша текста, одинаковый текст даёт одинаковый вектор. */
class HashEmbeddingProvider(override val dimension: Int = TestKnowledge.DIMENSION) : EmbeddingProvider {
    var documentCalls = 0

    override suspend fun embedQuery(text: String) = vector(text)

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> {
        documentCalls++
        return texts.map(::vector)
    }

    private fun vector(text: String): FloatArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return FloatArray(dimension) { (hash[it].toInt() + 128) / 255f + 0.01f }
    }
}

/** Хранилище в памяти: помнит, что и куда было записано. */
class RecordingKnowledgeWriter : KnowledgeBaseWriter {
    val documents = mutableMapOf<String, MutableMap<String, IndexedDocument>>()
    val deleted = mutableListOf<Pair<String?, String>>()
    var activeName: String = "active"

    override suspend fun replaceDocument(
        collection: String?,
        document: IndexedDocument,
    ) {
        documents.getOrPut(collection ?: activeName) { mutableMapOf() }[document.url] = document
    }

    override suspend fun deleteDocument(
        collection: String?,
        url: String,
    ) {
        deleted += collection to url
        documents[collection ?: activeName]?.remove(url)
    }
}

class FakeCollections(private val writer: RecordingKnowledgeWriter) : KnowledgeCollections {
    var activeCollection: String? = null
    val created = mutableListOf<String>()
    var dropCalls = 0

    override suspend fun active() = activeCollection

    override suspend fun create(): String = "collection-${created.size + 1}".also { created += it }

    override suspend fun ensureActive(): String = activeCollection ?: create().also { activate(it) }

    override suspend fun activate(collection: String) {
        activeCollection = collection
        writer.activeName = collection
    }

    override suspend fun dropOld() {
        dropCalls++
    }

    val discarded = mutableListOf<String>()

    override suspend fun discard(collection: String) {
        if (collection != activeCollection) discarded += collection
    }
}

class InMemorySourceDocumentRepository : SourceDocumentRepository {
    val rows = mutableMapOf<String, KnownDocument>()

    override suspend fun all(): Map<String, KnownDocument> = rows.toMap()

    override suspend fun save(
        url: String,
        title: String,
        contentHash: String,
        version: Long,
        chunkCount: Int,
        checkedAt: Instant,
    ) {
        rows[url] = KnownDocument(url, contentHash, version, chunkCount, checkedAt)
    }

    override suspend fun touch(
        url: String,
        checkedAt: Instant,
    ) {
        rows[url] = rows.getValue(url).copy(checkedAt = checkedAt)
    }

    override suspend fun delete(url: String) {
        rows.remove(url)
    }
}

class InMemoryIndexRunRepository : IndexRunRepository {
    private val ids = AtomicLong()
    val finished = mutableListOf<Triple<Long, IndexRunStatus, IndexCounters>>()

    override suspend fun start(
        mode: IndexMode,
        at: Instant,
    ): Long = ids.incrementAndGet()

    override suspend fun finish(
        id: Long,
        status: IndexRunStatus,
        counters: IndexCounters,
        message: String?,
        at: Instant,
    ) {
        finished += Triple(id, status, counters)
    }
}

object TestCrawler {
    fun settings(
        startUrl: String = "https://site.test",
        maxDepth: Int = 5,
        respectRobots: Boolean = true,
    ) = KnowledgeProperties.Crawler(
        startUrl = startUrl,
        maxDepth = maxDepth,
        delayBetweenRequests = Duration.ZERO,
        userAgent = "FpmiBotTest/1.0",
        requestTimeout = Duration.ofSeconds(5),
        maxRetries = 0,
        retryBackoff = Duration.ZERO,
        excludedExtensions = listOf(".pdf", ".jpg", ".png"),
        excludedPathParts = listOf("/admin", "/user"),
        respectRobotsTxt = respectRobots,
        extraCertificates = emptyList(),
    )

    val indexing = KnowledgeProperties.Indexing(
        cron = "0 0 3 * * *",
        indexOnEmptyStart = false,
        parallelPages = 2,
        maxFailedShare = 0.2,
        minPagesShareOfPrevious = 0.5,
    )

    /** Страница в формате сайта факультета: основное содержимое в `div#block-famcs-content`. */
    fun page(
        vararg sections: Pair<String, String>,
        links: List<String> = emptyList(),
    ): String {
        val body = sections.joinToString("") { (title, text) -> "<h2>$title</h2><p>$text</p>" }
        val anchors = links.joinToString("") { "<a href=\"$it\">ссылка</a>" }
        return "<html><head><title>Страница</title></head><body><div id=\"block-famcs-content\">$body$anchors</div></body></html>"
    }
}
