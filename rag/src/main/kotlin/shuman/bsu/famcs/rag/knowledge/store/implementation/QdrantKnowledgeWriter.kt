package shuman.bsu.famcs.rag.knowledge.store.implementation

import io.qdrant.client.ConditionFactory
import io.qdrant.client.PointIdFactory
import io.qdrant.client.QdrantClient
import io.qdrant.client.VectorFactory
import io.qdrant.client.VectorsFactory
import io.qdrant.client.grpc.Common.Filter
import io.qdrant.client.grpc.Points.PointStruct
import io.qdrant.client.grpc.Points.UpsertPoints
import kotlinx.coroutines.guava.await
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.model.IndexedChunk
import shuman.bsu.famcs.rag.knowledge.model.IndexedDocument
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseWriter
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeCollections
import java.util.UUID

/**
 * Запись фрагментов в Qdrant.
 *
 * Страница заменяется целиком по схеме «записать новую версию, затем удалить старую»: если запись упадёт посреди,
 * прежняя версия страницы остаётся на месте и поиск продолжает её находить.
 */
class QdrantKnowledgeWriter(
    private val client: QdrantClient,
    private val qdrant: KnowledgeProperties.Qdrant,
    private val collections: KnowledgeCollections,
) : KnowledgeBaseWriter {
    override suspend fun replaceDocument(
        collection: String?,
        document: IndexedDocument,
    ) {
        val target = resolve(collection)
        for (batch in document.chunks.chunked(qdrant.upsertBatchSize)) {
            val request = UpsertPoints
                .newBuilder()
                .setCollectionName(target)
                // wait=true: ответ приходит после того, как точки стали видны поиску. Иначе удаление старой версии могло бы обогнать запись.
                .setWait(true)
                .addAllPoints(batch.map { toPoint(it, document) })
                .build()
            client.upsertAsync(request, qdrant.requestTimeout).await()
        }
        val staleFilter = Filter
            .newBuilder()
            .addMust(ConditionFactory.matchKeyword(QdrantPayload.SOURCE, document.url))
            .addMustNot(ConditionFactory.match(QdrantPayload.VERSION, document.version))
            .build()
        client.deleteAsync(target, staleFilter, qdrant.requestTimeout).await()
    }

    override suspend fun deleteDocument(
        collection: String?,
        url: String,
    ) {
        val filter = Filter.newBuilder().addMust(ConditionFactory.matchKeyword(QdrantPayload.SOURCE, url)).build()
        client.deleteAsync(resolve(collection), filter, qdrant.requestTimeout).await()
    }

    /**
     * Определяет коллекцию записи: указанную явно или активную.
     *
     * @param collection имя коллекции или `null`.
     */
    private suspend fun resolve(collection: String?): String =
        collection ?: collections.active() ?: error("Нет активной коллекции знаний: индексация ещё не выполнялась")

    /**
     * Собирает точку Qdrant из фрагмента: смысловой и словарный векторы и данные для ответа.
     *
     * @param chunk фрагмент.
     * @param document документ, которому он принадлежит.
     */
    private fun toPoint(
        chunk: IndexedChunk,
        document: IndexedDocument,
    ): PointStruct = PointStruct
        .newBuilder()
        .setId(PointIdFactory.id(UUID.fromString(chunk.id)))
        .setVectors(
            VectorsFactory.namedVectors(
                mapOf(
                    qdrant.denseVectorName to VectorFactory.vector(chunk.dense.toList()),
                    qdrant.sparseVectorName to VectorFactory.vector(chunk.sparse.values.toList(), chunk.sparse.indices.toList()),
                ),
            ),
        ).putAllPayload(QdrantPayload.toPayload(chunk, document))
        .build()
}
