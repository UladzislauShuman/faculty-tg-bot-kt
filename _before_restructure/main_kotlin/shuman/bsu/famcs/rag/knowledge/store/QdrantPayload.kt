package shuman.bsu.famcs.rag.knowledge.store

import io.qdrant.client.ValueFactory
import io.qdrant.client.grpc.JsonWithInt.Value
import java.time.Instant

/**
 * Поля данных точки в Qdrant и их преобразование.
 *
 * Имена полей собраны в одном месте: запись, чтение и фильтры должны ссылаться на одни и те же строки.
 */
internal object QdrantPayload {
    const val TEXT = "text"
    const val PARENT_ID = "parent_id"
    const val PARENT_TEXT = "parent_text"
    const val SOURCE = "source"
    const val TITLE = "title"
    const val INDEXED_AT = "indexed_at"
    const val VERSION = "version"
    val HEADING_KEYS = listOf("H1", "H2", "H3")

    fun headingKey(name: String) = "heading_${name.lowercase()}"

    fun toPayload(
        chunk: IndexedChunk,
        document: IndexedDocument,
    ): Map<String, Value> = buildMap {
        put(TEXT, ValueFactory.value(chunk.text))
        put(PARENT_ID, ValueFactory.value(chunk.parentId))
        put(PARENT_TEXT, ValueFactory.value(chunk.parentText))
        put(SOURCE, ValueFactory.value(document.url))
        put(TITLE, ValueFactory.value(document.title))
        put(INDEXED_AT, ValueFactory.value(document.indexedAt.toString()))
        put(VERSION, ValueFactory.value(document.version))
        chunk.headings.forEach { (name, text) -> put(headingKey(name), ValueFactory.value(text)) }
    }

    fun toHit(
        id: String,
        score: Double,
        payload: Map<String, Value>,
    ): SearchHit {
        fun text(key: String): String = payload[key]?.stringValue.orEmpty()
        return SearchHit(
            id = id,
            score = score,
            text = text(TEXT),
            parentId = text(PARENT_ID),
            parentText = text(PARENT_TEXT).ifEmpty { text(TEXT) },
            url = text(SOURCE),
            title = text(TITLE),
            headings = HEADING_KEYS.mapNotNull { name -> payload[headingKey(name)]?.stringValue?.let { name to it } }.toMap(),
            indexedAt = text(INDEXED_AT).takeIf { it.isNotEmpty() }?.let(Instant::parse),
        )
    }
}
