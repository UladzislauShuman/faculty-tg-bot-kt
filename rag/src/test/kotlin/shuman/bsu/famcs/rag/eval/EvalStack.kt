package shuman.bsu.famcs.rag.eval

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.qdrant.client.QdrantClient
import kotlinx.coroutines.Dispatchers
import shuman.bsu.famcs.rag.knowledge.configuration.KnowledgeConfiguration
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.onnx.OnnxSession
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.platform.runtime.AppDispatchers

/**
 * Настоящий стек поиска для проверок качества: те же модели ONNX, тот же Qdrant и те же настройки из `application.yaml`,
 * что и у бота, но без Spring, Postgres и языковых моделей.
 *
 * Бины собираются теми же методами [KnowledgeConfiguration], поэтому проверка идёт по настоящей сборке, а не по копии.
 * Нужны запущенный Qdrant на адресе из настроек (с проиндексированной базой) и файлы моделей в `models/`.
 */
class EvalStack : AutoCloseable {
    private val properties: KnowledgeProperties = EvalConfig.knowledge()
    private val config = KnowledgeConfiguration()
    private val dispatchers = AppDispatchers(cpu = Dispatchers.Default, database = Dispatchers.IO)

    private val embeddingSession: OnnxSession = config.embeddingModel(properties)
    private val rerankerSession: OnnxSession? = if (properties.reranker.enabled) config.rerankerModel(properties) else null
    private val client: QdrantClient = config.qdrantClient(properties)

    /** Смысловые векторы запросов. */
    val embeddings: EmbeddingProvider = config.embeddingProvider(embeddingSession, properties, dispatchers)

    /** Кодировщик BM25. */
    val sparse: Bm25SparseEncoder = config.sparseEncoder(config.textAnalyzer(properties), properties)

    /** Чтение из активной коллекции Qdrant. */
    val reader: KnowledgeBaseReader = config.knowledgeReader(client, properties)

    /** Реранкер (кросс-энкодер); `null`, если выключен в настройках. */
    val reranker: Reranker? = rerankerSession?.let { config.reranker(it, properties, dispatchers) }

    /** Сколько разделов оставляет реранкер (`bot.knowledge.reranker.top-n`). */
    val topN: Int = properties.reranker.topN

    /** Поиск целиком: гибридный поиск, родители, реранкер. */
    val retriever = KnowledgeRetriever(
        embeddings = embeddings,
        sparse = sparse,
        reader = reader,
        reranker = reranker,
        topN = properties.reranker.topN,
        meters = SimpleMeterRegistry(),
    )

    override fun close() {
        client.close()
        rerankerSession?.close()
        embeddingSession.close()
    }
}
