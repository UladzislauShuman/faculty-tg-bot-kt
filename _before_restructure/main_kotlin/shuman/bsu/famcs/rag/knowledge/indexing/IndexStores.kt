package shuman.bsu.famcs.rag.knowledge.indexing

import shuman.bsu.famcs.rag.common.Database
import java.sql.Timestamp
import java.time.Instant

/** Что известно о ранее проиндексированной странице. */
data class KnownDocument(
    val url: String,
    /** Хеш нормализованного текста страницы. Совпал с прежним — эмбеддинги пересчитывать не нужно. */
    val contentHash: String,
    val version: Long,
    val chunkCount: Int,
    val checkedAt: Instant,
)

/** Учёт проиндексированных страниц: по нему индексатор понимает, что изменилось с прошлого раза. */
interface SourceDocumentStore {
    suspend fun all(): Map<String, KnownDocument>

    suspend fun save(
        url: String,
        title: String,
        contentHash: String,
        version: Long,
        chunkCount: Int,
        checkedAt: Instant,
    )

    /** Страница проверена и не изменилась: обновляется только дата проверки. */
    suspend fun touch(
        url: String,
        checkedAt: Instant,
    )

    suspend fun delete(url: String)
}

/** Итоги запуска индексации. */
data class IndexCounters(
    val found: Int,
    val changed: Int,
    val failed: Int,
    val removed: Int,
)

enum class IndexMode { INCREMENTAL, FULL }

enum class IndexRunStatus { RUNNING, SUCCEEDED, ABORTED, FAILED }

/** Журнал запусков индексации. Номер запуска служит версией данных в Qdrant. */
interface IndexRunStore {
    suspend fun start(
        mode: IndexMode,
        at: Instant,
    ): Long

    suspend fun finish(
        id: Long,
        status: IndexRunStatus,
        counters: IndexCounters,
        message: String?,
        at: Instant,
    )
}

class JdbcSourceDocumentStore(
    private val db: Database,
) : SourceDocumentStore {
    override suspend fun all(): Map<String, KnownDocument> = db.query { jdbc ->
        jdbc
            .sql("SELECT url, content_hash, version, chunk_count, checked_at FROM source_document")
            .query { rs, _ ->
                KnownDocument(
                    url = rs.getString("url"),
                    contentHash = rs.getString("content_hash"),
                    version = rs.getLong("version"),
                    chunkCount = rs.getInt("chunk_count"),
                    checkedAt = rs.getTimestamp("checked_at").toInstant(),
                )
            }.list()
            .associateBy { it.url }
    }

    override suspend fun save(
        url: String,
        title: String,
        contentHash: String,
        version: Long,
        chunkCount: Int,
        checkedAt: Instant,
    ) {
        db.query { jdbc ->
            jdbc
                .sql(
                    """
                INSERT INTO source_document (url, content_hash, title, checked_at, version, chunk_count)
                VALUES (:url, :hash, :title, :checkedAt, :version, :chunkCount)
                ON CONFLICT (url) DO UPDATE SET content_hash = :hash, title = :title, checked_at = :checkedAt,
                                                version = :version, chunk_count = :chunkCount
                    """.trimIndent(),
                ).param("url", url)
                .param("hash", contentHash)
                .param("title", title)
                .param("checkedAt", Timestamp.from(checkedAt))
                .param("version", version)
                .param("chunkCount", chunkCount)
                .update()
        }
    }

    override suspend fun touch(
        url: String,
        checkedAt: Instant,
    ) {
        db.query { jdbc ->
            jdbc
                .sql("UPDATE source_document SET checked_at = :checkedAt WHERE url = :url")
                .param("checkedAt", Timestamp.from(checkedAt))
                .param("url", url)
                .update()
        }
    }

    override suspend fun delete(url: String) {
        db.query { jdbc -> jdbc.sql("DELETE FROM source_document WHERE url = :url").param("url", url).update() }
    }
}

class JdbcIndexRunStore(
    private val db: Database,
) : IndexRunStore {
    override suspend fun start(
        mode: IndexMode,
        at: Instant,
    ): Long = db.query { jdbc ->
        jdbc
            .sql("INSERT INTO index_run (mode, started_at, status) VALUES (:mode, :at, 'RUNNING') RETURNING id")
            .param("mode", mode.name)
            .param("at", Timestamp.from(at))
            .query(Long::class.java)
            .single()
    }

    override suspend fun finish(
        id: Long,
        status: IndexRunStatus,
        counters: IndexCounters,
        message: String?,
        at: Instant,
    ) {
        db.query { jdbc ->
            jdbc
                .sql(
                    """
                UPDATE index_run SET finished_at = :at, status = :status, pages_found = :found, pages_changed = :changed,
                                     pages_failed = :failed, pages_removed = :removed, message = :message
                WHERE id = :id
                    """.trimIndent(),
                ).param("at", Timestamp.from(at))
                .param("status", status.name)
                .param("found", counters.found)
                .param("changed", counters.changed)
                .param("failed", counters.failed)
                .param("removed", counters.removed)
                .param("message", message)
                .param("id", id)
                .update()
        }
    }
}
