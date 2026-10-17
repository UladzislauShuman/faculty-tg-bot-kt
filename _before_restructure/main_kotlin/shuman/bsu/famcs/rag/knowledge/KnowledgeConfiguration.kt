package shuman.bsu.famcs.rag.knowledge

import io.micrometer.core.instrument.MeterRegistry
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.app.AppDispatchers
import shuman.bsu.famcs.rag.app.ApplicationScope
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.core.AdminNotifier
import shuman.bsu.famcs.rag.knowledge.KnowledgeProperties.AnalyzerType
import shuman.bsu.famcs.rag.knowledge.chunking.Chunker
import shuman.bsu.famcs.rag.knowledge.chunking.HtmlToMarkdown
import shuman.bsu.famcs.rag.knowledge.chunking.MarkdownHeaderSplitter
import shuman.bsu.famcs.rag.knowledge.chunking.ParentChildHtmlChunker
import shuman.bsu.famcs.rag.knowledge.chunking.RecursiveCharacterSplitter
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.embedding.OnnxEmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.indexing.IndexRunStore
import shuman.bsu.famcs.rag.knowledge.indexing.Indexer
import shuman.bsu.famcs.rag.knowledge.indexing.IndexingJob
import shuman.bsu.famcs.rag.knowledge.indexing.JdbcIndexRunStore
import shuman.bsu.famcs.rag.knowledge.indexing.JdbcSourceDocumentStore
import shuman.bsu.famcs.rag.knowledge.indexing.SourceDocumentStore
import shuman.bsu.famcs.rag.knowledge.onnx.OnnxSession
import shuman.bsu.famcs.rag.knowledge.rerank.OnnxCrossEncoderReranker
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.source.DataSource
import shuman.bsu.famcs.rag.knowledge.source.HttpPageFetcher
import shuman.bsu.famcs.rag.knowledge.source.PageFetcher
import shuman.bsu.famcs.rag.knowledge.source.SiteCrawler
import shuman.bsu.famcs.rag.knowledge.source.TlsContexts
import shuman.bsu.famcs.rag.knowledge.source.WebsiteDataSource
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.FastEmbedCompatibleAnalyzer
import shuman.bsu.famcs.rag.knowledge.sparse.RussianTextAnalyzer
import shuman.bsu.famcs.rag.knowledge.sparse.TextAnalyzer
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseWriter
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeCollections
import shuman.bsu.famcs.rag.knowledge.store.QdrantCollections
import shuman.bsu.famcs.rag.knowledge.store.QdrantKnowledgeReader
import shuman.bsu.famcs.rag.knowledge.store.QdrantKnowledgeWriter
import java.net.http.HttpClient
import java.time.Clock

/**
 * Сборка модуля знаний: модели, поиск, хранилище, обход сайта, индексатор.
 *
 * Каждая заменяемая часть создаётся как отдельный бин за своим интерфейсом: чтобы подставить другую модель эмбеддингов,
 * другое хранилище или новый источник, достаточно заменить один бин.
 */
@Configuration(proxyBeanMethods = false)
class KnowledgeConfiguration {
    // --- Модели на процессоре ---

    @Bean(destroyMethod = "close")
    fun embeddingModel(properties: KnowledgeProperties): OnnxSession = properties.embedding.let {
        OnnxSession(it.modelPath, it.tokenizerPath, maxTokens = it.maxTokens, intraOpThreads = it.intraOpThreads)
    }

    @Bean
    fun embeddingProvider(
        embeddingModel: OnnxSession,
        properties: KnowledgeProperties,
        dispatchers: AppDispatchers,
    ): EmbeddingProvider = OnnxEmbeddingProvider(
        onnx = embeddingModel,
        dimension = properties.embedding.dimension,
        batchSize = properties.embedding.batchSize,
        outputName = properties.embedding.outputName,
        dispatcher = dispatchers.cpu,
    )

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty("bot.knowledge.reranker.enabled", havingValue = "true")
    fun rerankerModel(properties: KnowledgeProperties): OnnxSession = properties.reranker.let {
        OnnxSession(it.modelPath, it.tokenizerPath, maxTokens = it.maxTokens, intraOpThreads = it.intraOpThreads)
    }

    @Bean
    @ConditionalOnProperty("bot.knowledge.reranker.enabled", havingValue = "true")
    fun reranker(
        rerankerModel: OnnxSession,
        properties: KnowledgeProperties,
        dispatchers: AppDispatchers,
    ): Reranker = OnnxCrossEncoderReranker(
        onnx = rerankerModel,
        batchSize = properties.reranker.batchSize,
        outputName = properties.reranker.outputName,
        dispatcher = dispatchers.cpu,
    )

    // --- Поиск по ключевым словам ---

    @Bean
    fun textAnalyzer(properties: KnowledgeProperties): TextAnalyzer = when (properties.sparse.analyzer) {
        AnalyzerType.FASTEMBED_COMPATIBLE -> FastEmbedCompatibleAnalyzer(
            stopWords = requireNotNull(KnowledgeConfiguration::class.java.getResource("/knowledge/stopwords-english.txt")) {
                "Нет файла стоп-слов knowledge/stopwords-english.txt"
            }.readText().lines().map(String::trim).filter(String::isNotEmpty).toSet(),
            maxTokenLength = properties.sparse.maxTokenLength,
        )

        AnalyzerType.RUSSIAN -> RussianTextAnalyzer(maxTokenLength = properties.sparse.maxTokenLength)
    }

    @Bean
    fun sparseEncoder(
        analyzer: TextAnalyzer,
        properties: KnowledgeProperties,
    ) = Bm25SparseEncoder(
        analyzer = analyzer,
        k = properties.sparse.k,
        b = properties.sparse.b,
        averageLength = properties.sparse.averageLength,
    )

    // --- Qdrant ---

    @Bean(destroyMethod = "close")
    fun qdrantClient(properties: KnowledgeProperties): QdrantClient {
        val qdrant = properties.qdrant
        val grpc = QdrantGrpcClient
            .newBuilder(qdrant.host, qdrant.grpcPort, qdrant.useTls)
            .withTimeout(qdrant.requestTimeout)
            .apply { qdrant.apiKey?.takeIf { it.isNotBlank() }?.let(::withApiKey) }
            .build()
        return QdrantClient(grpc)
    }

    @Bean
    fun knowledgeCollections(
        client: QdrantClient,
        properties: KnowledgeProperties,
        clock: Clock,
    ): KnowledgeCollections = QdrantCollections(client, properties.qdrant, properties.sparse, properties.embedding.dimension, clock)

    @Bean
    fun knowledgeReader(
        client: QdrantClient,
        properties: KnowledgeProperties,
    ): KnowledgeBaseReader = QdrantKnowledgeReader(client, properties.qdrant, properties.search)

    @Bean
    fun knowledgeWriter(
        client: QdrantClient,
        properties: KnowledgeProperties,
        collections: KnowledgeCollections,
    ): KnowledgeBaseWriter = QdrantKnowledgeWriter(client, properties.qdrant, collections)

    // --- Поиск знаний по вопросу ---

    @Bean
    fun knowledgeRetriever(
        embeddings: EmbeddingProvider,
        sparse: Bm25SparseEncoder,
        reader: KnowledgeBaseReader,
        reranker: ObjectProvider<Reranker>,
        properties: KnowledgeProperties,
        meters: MeterRegistry,
    ) = KnowledgeRetriever(
        embeddings = embeddings,
        sparse = sparse,
        reader = reader,
        reranker = reranker.ifAvailable,
        topN = properties.reranker.topN,
        meters = meters,
    )

    // --- Источники и индексация ---

    @Bean
    fun chunker(properties: KnowledgeProperties): Chunker = properties.chunking.let { chunking ->
        ParentChildHtmlChunker(
            converter = HtmlToMarkdown(chunking.escapeAsterisks, chunking.escapeUnderscores, chunking.bullets, chunking.absolutizeLinks),
            headerSplitter = MarkdownHeaderSplitter(
                (1..chunking.headerLevels).map { MarkdownHeaderSplitter.Header("#".repeat(it), "H$it") },
            ),
            childSplitter = RecursiveCharacterSplitter(chunking.childSize, chunking.childOverlap, chunking.childSeparators),
            contentSelectors = chunking.contentSelectors,
            removedTags = chunking.removedTags,
        )
    }

    @Bean
    fun pageFetcher(
        properties: KnowledgeProperties,
        clock: Clock,
    ): PageFetcher {
        val crawler = properties.crawler
        val client = HttpClient
            .newBuilder()
            .sslContext(TlsContexts.withExtraCertificates(crawler.extraCertificates))
            .connectTimeout(crawler.requestTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        return HttpPageFetcher(client, crawler, clock)
    }

    @Bean
    fun websiteDataSource(
        fetcher: PageFetcher,
        properties: KnowledgeProperties,
    ): DataSource = WebsiteDataSource(SiteCrawler(fetcher, properties.crawler), fetcher)

    @Bean
    fun sourceDocumentStore(database: Database): SourceDocumentStore = JdbcSourceDocumentStore(database)

    @Bean
    fun indexRunStore(database: Database): IndexRunStore = JdbcIndexRunStore(database)

    @Bean
    fun indexer(
        sources: List<DataSource>,
        chunkers: List<Chunker>,
        embeddings: EmbeddingProvider,
        sparse: Bm25SparseEncoder,
        writer: KnowledgeBaseWriter,
        collections: KnowledgeCollections,
        documents: SourceDocumentStore,
        runs: IndexRunStore,
        properties: KnowledgeProperties,
        clock: Clock,
        meters: MeterRegistry,
    ) = Indexer(sources, chunkers, embeddings, sparse, writer, collections, documents, runs, properties.indexing, clock, meters)

    @Bean
    fun indexingJob(
        indexer: Indexer,
        scope: ApplicationScope,
        notifier: AdminNotifier,
    ) = IndexingJob(indexer, scope, notifier)
}
