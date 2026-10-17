package shuman.bsu.famcs.rag.knowledge.indexing.repository.jdbc

import shuman.bsu.famcs.rag.knowledge.indexing.repository.SourceDocumentRepository
import shuman.bsu.famcs.rag.knowledge.model.KnownDocument
import shuman.bsu.famcs.rag.platform.database.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Реализация [SourceDocumentRepository] на PostgreSQL (таблица `source_document`).
 *
 * @property db доступ к базе.
 */
class SourceDocumentRepositoryJdbcImpl(
    private val db: Database,
) : SourceDocumentRepository {
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
