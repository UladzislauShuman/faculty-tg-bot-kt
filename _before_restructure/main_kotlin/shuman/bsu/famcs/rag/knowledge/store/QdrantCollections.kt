package shuman.bsu.famcs.rag.knowledge.store

import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections.AliasOperations
import io.qdrant.client.grpc.Collections.CreateAlias
import io.qdrant.client.grpc.Collections.CreateCollection
import io.qdrant.client.grpc.Collections.DeleteAlias
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.HnswConfigDiff
import io.qdrant.client.grpc.Collections.Modifier
import io.qdrant.client.grpc.Collections.PayloadSchemaType
import io.qdrant.client.grpc.Collections.SparseVectorConfig
import io.qdrant.client.grpc.Collections.SparseVectorParams
import io.qdrant.client.grpc.Collections.VectorParams
import io.qdrant.client.grpc.Collections.VectorParamsMap
import io.qdrant.client.grpc.Collections.VectorsConfig
import kotlinx.coroutines.guava.await
import shuman.bsu.famcs.rag.knowledge.KnowledgeProperties
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * Управляет физическими коллекциями Qdrant и алиасом, по которому ищет бот.
 *
 * Зачем алиас: полная пересборка индекса (новая модель эмбеддингов, новая нарезка) пишется в НОВУЮ коллекцию, пока бот
 * продолжает отвечать по старой. Когда новая готова и проверена, алиас переключается одним атомарным запросом.
 * Откат — переключить алиас обратно на прежнюю коллекцию, она хранится ещё некоторое время.
 */
class QdrantCollections(
    private val client: QdrantClient,
    private val qdrant: KnowledgeProperties.Qdrant,
    private val sparse: KnowledgeProperties.Sparse,
    /** Размер плотного вектора: определяется моделью эмбеддингов. */
    private val denseDimension: Int,
    private val clock: Clock,
) : KnowledgeCollections {
    private val alias get() = qdrant.collectionAlias

    override suspend fun active(): String? =
        client
            .listAliasesAsync(qdrant.requestTimeout)
            .await()
            .firstOrNull { it.aliasName == alias }
            ?.collectionName

    override suspend fun create(): String {
        val name = "${alias}_${NAME_TIME.format(clock.instant().atOffset(ZoneOffset.UTC))}"
        val request = CreateCollection
            .newBuilder()
            .setCollectionName(name)
            .setVectorsConfig(
                VectorsConfig.newBuilder().setParamsMap(
                    VectorParamsMap.newBuilder().putMap(
                        qdrant.denseVectorName,
                        VectorParams
                            .newBuilder()
                            .setSize(denseDimension.toLong())
                            // Векторы bge-m3 нормализованы: косинусная близость — стандартная мера.
                            .setDistance(Distance.Cosine)
                            .setHnswConfig(
                                HnswConfigDiff
                                    .newBuilder()
                                    .setM(qdrant.hnswM.toLong())
                                    .setEfConstruct(qdrant.hnswEfConstruct.toLong())
                                    .build(),
                            )
                            // Корпус мал (десятки мегабайт): векторы держим в памяти, а не на диске.
                            .setOnDisk(false)
                            .build(),
                    ),
                ),
            ).setSparseVectorsConfig(
                SparseVectorConfig.newBuilder().putMap(
                    qdrant.sparseVectorName,
                    SparseVectorParams
                        .newBuilder()
                        // IDF придаёт больший вес редким словам запроса. В baseline модификатор не был включён.
                        .setModifier(if (sparse.idfModifier) Modifier.Idf else Modifier.None)
                        .build(),
                ),
            ).setOnDiskPayload(false)
            .build()
        client.createCollectionAsync(request).await()

        // Индексы по полям, которые используются в фильтрах: удаление прежней версии страницы идёт по адресу и версии.
        client
            .createPayloadIndexAsync(
                name,
                QdrantPayload.SOURCE,
                PayloadSchemaType.Keyword,
                null,
                true,
                null,
                qdrant.requestTimeout,
            ).await()
        client
            .createPayloadIndexAsync(
                name,
                QdrantPayload.VERSION,
                PayloadSchemaType.Integer,
                null,
                true,
                null,
                qdrant.requestTimeout,
            ).await()
        log.info { "Создана коллекция $name (размер вектора $denseDimension)" }
        return name
    }

    override suspend fun activate(collection: String) {
        val operations = buildList {
            if (active() != null) {
                add(AliasOperations.newBuilder().setDeleteAlias(DeleteAlias.newBuilder().setAliasName(alias)).build())
            }
            add(
                AliasOperations
                    .newBuilder()
                    .setCreateAlias(
                        CreateAlias.newBuilder().setAliasName(alias).setCollectionName(collection),
                    ).build(),
            )
        }
        // Удаление и создание алиаса идут одним запросом: поиск никогда не остаётся без коллекции.
        client.updateAliasesAsync(operations, qdrant.requestTimeout).await()
        log.info { "Алиас $alias переключён на $collection" }
    }

    override suspend fun dropOld() {
        val current = active()
        val others = client
            .listCollectionsAsync(qdrant.requestTimeout)
            .await()
            .filter { it.startsWith("${alias}_") && it != current }
            .sortedDescending()
        others.drop(qdrant.keepOldCollections).forEach { name ->
            client.deleteCollectionAsync(name, qdrant.requestTimeout).await()
            log.info { "Удалена старая коллекция $name" }
        }
    }

    override suspend fun discard(collection: String) {
        // Защита от ошибки вызова: рабочую коллекцию через эту дверь удалить нельзя.
        if (collection == active()) return
        client.deleteCollectionAsync(collection, qdrant.requestTimeout).await()
        log.info { "Удалена незавершённая коллекция $collection" }
    }

    override suspend fun ensureActive(): String = active() ?: create().also { activate(it) }

    private companion object {
        val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
