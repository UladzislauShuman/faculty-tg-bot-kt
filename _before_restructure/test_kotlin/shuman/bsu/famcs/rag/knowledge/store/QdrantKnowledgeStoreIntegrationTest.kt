package shuman.bsu.famcs.rag.knowledge.store

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.qdrant.QdrantContainer
import shuman.bsu.famcs.rag.knowledge.SparseVector
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.TestKnowledge
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Интеграционный тест хранилища знаний на настоящем Qdrant в контейнере (нужен Docker).
 * Проверяет схему коллекции, гибридный запрос, замену версии страницы и переключение алиаса.
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QdrantKnowledgeStoreIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val qdrantContainer = QdrantContainer("qdrant/qdrant:v1.17.1")
    }

    private lateinit var client: QdrantClient
    private val clock = MutableClock()

    @BeforeAll
    fun connect() {
        client = QdrantClient(QdrantGrpcClient.newBuilder(qdrantContainer.host, qdrantContainer.getMappedPort(6334), false).build())
    }

    @AfterAll
    fun disconnect() {
        client.close()
    }

    private fun newStore(alias: String): Triple<QdrantCollections, QdrantKnowledgeWriter, QdrantKnowledgeReader> {
        val qdrant = TestKnowledge.qdrant(qdrantContainer.host, qdrantContainer.getMappedPort(6334), alias)
        val collections = QdrantCollections(client, qdrant, TestKnowledge.sparse, TestKnowledge.DIMENSION, clock)
        return Triple(
            collections,
            QdrantKnowledgeWriter(client, qdrant, collections),
            QdrantKnowledgeReader(client, qdrant, TestKnowledge.search),
        )
    }

    private val towardsX = floatArrayOf(1f, 0f, 0f, 0f)
    private val towardsY = floatArrayOf(0f, 1f, 0f, 0f)
    private val towardsZ = floatArrayOf(0f, 0f, 1f, 0f)

    @Test
    fun `поиск находит фрагмент по смыслу и отдаёт родителя, заголовки и источник`() = runBlocking<Unit> {
        val (collections, writer, reader) = newStore("kb_basic")
        collections.activate(collections.create())
        writer.replaceDocument(
            null,
            TestKnowledge.document(
                "https://site/a",
                1,
                TestKnowledge.chunk("https://site/a", "про практику", towardsX, intArrayOf(10)),
                TestKnowledge.chunk("https://site/a", "про экзамены", towardsY, intArrayOf(20)),
            ),
        )

        val hits = reader.search(HybridSearchRequest(dense = towardsX, sparse = SparseVector(intArrayOf(999), floatArrayOf(1f))))

        val best = hits.first()
        assertEquals("про практику", best.text)
        assertEquals("Родительский раздел: про практику", best.parentText)
        assertEquals("https://site/a", best.url)
        assertEquals("Раздел", best.headings["H2"])
        assertNotNull(best.indexedAt)
    }

    @Test
    fun `до первой индексации размер базы равен нулю без ошибки`() = runBlocking<Unit> {
        val (_, _, reader) = newStore("kb_never_created")

        assertEquals(0L, reader.chunkCount())
    }

    @Test
    fun `гибридный запрос объединяет совпадение по смыслу и совпадение по словам`() = runBlocking<Unit> {
        val (collections, writer, reader) = newStore("kb_hybrid")
        collections.activate(collections.create())
        writer.replaceDocument(
            null,
            TestKnowledge.document(
                "https://site/h",
                1,
                TestKnowledge.chunk("https://site/h", "близок по смыслу", towardsX, intArrayOf(1)),
                TestKnowledge.chunk("https://site/h", "содержит нужное слово", towardsZ, intArrayOf(42)),
                TestKnowledge.chunk("https://site/h", "посторонний", towardsY, intArrayOf(7)),
            ),
        )

        val hits = reader.search(HybridSearchRequest(dense = towardsX, sparse = SparseVector(intArrayOf(42), floatArrayOf(1f))))

        val texts = hits.map { it.text }
        assertTrue(
            "близок по смыслу" in texts.take(2) && "содержит нужное слово" in texts.take(2),
            "оба сильных кандидата в начале: $texts",
        )
    }

    @Test
    fun `замена страницы удаляет фрагменты прежней версии, но не трогает другие страницы`() = runBlocking<Unit> {
        val (collections, writer, reader) = newStore("kb_replace")
        collections.activate(collections.create())
        writer.replaceDocument(
            null,
            TestKnowledge.document("https://site/p1", 1, TestKnowledge.chunk("https://site/p1", "старый текст", towardsX, intArrayOf(1))),
        )
        writer.replaceDocument(
            null,
            TestKnowledge.document(
                "https://site/p2",
                1,
                TestKnowledge.chunk("https://site/p2", "соседняя страница", towardsY, intArrayOf(2)),
            ),
        )

        writer.replaceDocument(
            null,
            TestKnowledge.document("https://site/p1", 2, TestKnowledge.chunk("https://site/p1", "новый текст", towardsX, intArrayOf(3))),
        )

        val texts = reader.search(HybridSearchRequest(towardsX, SparseVector(intArrayOf(3), floatArrayOf(1f)))).map { it.text }
        assertTrue("новый текст" in texts && "соседняя страница" in texts)
        assertTrue("старый текст" !in texts, "прежняя версия удалена: $texts")
        assertEquals(2, reader.chunkCount())
    }

    @Test
    fun `удаление страницы убирает её фрагменты`() = runBlocking<Unit> {
        val (collections, writer, reader) = newStore("kb_delete")
        collections.activate(collections.create())
        writer.replaceDocument(
            null,
            TestKnowledge.document("https://site/gone", 1, TestKnowledge.chunk("https://site/gone", "исчезнет", towardsX, intArrayOf(1))),
        )

        writer.deleteDocument(null, "https://site/gone")

        assertEquals(0, reader.chunkCount())
    }

    @Test
    fun `алиас атомарно переключается на новую коллекцию, а старые удаляются по лимиту`() = runBlocking<Unit> {
        val (collections, writer, reader) = newStore("kb_alias")
        val first = collections.create()
        collections.activate(first)
        writer.replaceDocument(
            first,
            TestKnowledge.document("https://site/v", 1, TestKnowledge.chunk("https://site/v", "версия один", towardsX, intArrayOf(1))),
        )

        clock.advance(Duration.ofSeconds(5))
        val second = collections.create()
        writer.replaceDocument(
            second,
            TestKnowledge.document("https://site/v", 1, TestKnowledge.chunk("https://site/v", "версия два", towardsX, intArrayOf(1))),
        )
        assertEquals(
            "версия один",
            reader.search(HybridSearchRequest(towardsX, SparseVector(intArrayOf(1), floatArrayOf(1f)))).first().text,
        )

        collections.activate(second)
        assertEquals(second, collections.active())
        assertEquals("версия два", reader.search(HybridSearchRequest(towardsX, SparseVector(intArrayOf(1), floatArrayOf(1f)))).first().text)

        clock.advance(Duration.ofSeconds(5))
        val third = collections.create()
        collections.activate(third)
        collections.dropOld()
        val remaining = client.listCollectionsAsync().get().filter { it.startsWith("kb_alias_") }
        assertEquals(setOf(third, second), remaining.toSet(), "остаются активная и одна прежняя (keepOldCollections = 1)")
    }
}
