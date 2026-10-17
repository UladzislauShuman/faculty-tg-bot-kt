package shuman.bsu.famcs.rag.knowledge.model

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
