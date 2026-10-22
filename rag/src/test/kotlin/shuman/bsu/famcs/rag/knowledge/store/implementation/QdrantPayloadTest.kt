package shuman.bsu.famcs.rag.knowledge.store.implementation

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.model.IndexedChunk
import shuman.bsu.famcs.rag.knowledge.model.IndexedDocument
import shuman.bsu.famcs.rag.knowledge.model.PageType
import shuman.bsu.famcs.rag.knowledge.model.SparseVector
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Запись полей фрагмента в данные точки Qdrant и обратное чтение. */
class QdrantPayloadTest {
    private val chunk =
        IndexedChunk("c1", "текст", "p1", "раздел", mapOf("H2" to "Сроки"), floatArrayOf(0.1f), SparseVector(intArrayOf(), floatArrayOf()))

    private fun document(orphan: Boolean) = IndexedDocument(
        "https://site.test/rules",
        "Правила",
        Instant.parse("2026-10-21T03:00:00Z"),
        1,
        listOf(chunk),
        PageType.SECTION,
        orphan = orphan,
    )

    @Test
    fun `признак сироты записывается и читается обратно, у обычной страницы поля нет`() {
        val orphanPayload = QdrantPayload.toPayload(chunk, document(orphan = true))
        val linkedPayload = QdrantPayload.toPayload(chunk, document(orphan = false))

        assertTrue(QdrantPayload.toHit("c1", 1.0, orphanPayload).orphan)
        assertFalse(QdrantPayload.ORPHAN in linkedPayload, "поле пишется только для сирот")
        assertFalse(QdrantPayload.toHit("c1", 1.0, linkedPayload).orphan)
        assertEquals("Сроки", QdrantPayload.toHit("c1", 1.0, orphanPayload).headings["H2"])
    }
}
