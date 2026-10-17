package shuman.bsu.famcs.rag.knowledge.store.implementation

import io.qdrant.client.QdrantClient
import io.qdrant.client.QueryFactory
import io.qdrant.client.WithPayloadSelectorFactory
import io.qdrant.client.grpc.Points.Fusion
import io.qdrant.client.grpc.Points.PrefetchQuery
import io.qdrant.client.grpc.Points.QueryPoints
import io.qdrant.client.grpc.Points.Rrf
import io.qdrant.client.grpc.Points.SearchParams
import kotlinx.coroutines.guava.await
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties.FusionMode
import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.model.SearchHit
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader

/**
 * Гибридный поиск в Qdrant одним запросом.
 *
 * Два поиска идут внутри Qdrant: по смыслу (dense-вектор) и по словам (sparse-вектор BM25). Каждый возвращает свой десяток
 * кандидатов, и сервер сливает списки по рангу (RRF). Результат читается вместе с родительским разделом: он лежит в данных
 * точки, второй запрос не нужен.
 */
class QdrantKnowledgeReader(
    private val client: QdrantClient,
    private val qdrant: KnowledgeProperties.Qdrant,
    private val search: KnowledgeProperties.Search,
) : KnowledgeBaseReader {
    override suspend fun search(request: HybridSearchRequest): List<SearchHit> {
        val searchParams = SearchParams
            .newBuilder()
            .setHnswEf(search.hnswEf.toLong())
            // Приближённый поиск по графу: на корпусе в тысячи фрагментов он неотличим от точного, но быстрее.
            .setExact(false)
            .build()

        val query = QueryPoints
            .newBuilder()
            .setCollectionName(qdrant.collectionAlias)
            .addPrefetch(
                PrefetchQuery
                    .newBuilder()
                    .setQuery(QueryFactory.nearest(request.sparse.values.toList(), request.sparse.indices.toList()))
                    .setUsing(qdrant.sparseVectorName)
                    .setLimit(search.candidatesPerLeg.toLong())
                    .build(),
            ).addPrefetch(
                PrefetchQuery
                    .newBuilder()
                    .setQuery(QueryFactory.nearest(request.dense.toList()))
                    .setUsing(qdrant.denseVectorName)
                    .setParams(searchParams)
                    .setLimit(search.candidatesPerLeg.toLong())
                    .build(),
            ).setQuery(fusionQuery())
            .setLimit(search.resultLimit.toLong())
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build()

        return client.queryAsync(query, qdrant.requestTimeout).await().map { point ->
            QdrantPayload.toHit(point.id.uuid, point.score.toDouble(), point.payloadMap)
        }
    }

    override suspend fun chunkCount(): Long {
        // До первой индексации алиаса нет. Это штатный случай: база пуста, а клиент Qdrant не должен писать ошибку в журнал.
        val aliasExists = client.listAliasesAsync(qdrant.requestTimeout).await().any { it.aliasName == qdrant.collectionAlias }
        return if (aliasExists) client.countAsync(qdrant.collectionAlias, null, true, qdrant.requestTimeout).await() else 0L
    }

    /** RRF без весов повторяет baseline; взвешенный вариант включается настройкой как эксперимент. */
    private fun fusionQuery() = when (search.fusion) {
        FusionMode.RRF -> QueryFactory.fusion(Fusion.RRF)

        FusionMode.WEIGHTED_RRF -> QueryFactory.rrf(
            Rrf
                .newBuilder()
                .setK(search.rrfK)
                // Порядок весов совпадает с порядком запросов в prefetch: сначала sparse, затем dense.
                .addWeights(search.sparseWeight)
                .addWeights(search.denseWeight)
                .build(),
        )
    }
}
