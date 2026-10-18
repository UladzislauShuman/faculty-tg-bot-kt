package shuman.bsu.famcs.rag.knowledge.configuration

import io.micrometer.core.instrument.MeterRegistry
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.knowledge.chunking.Chunker
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.HtmlToMarkdown
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.MarkdownHeaderSplitter
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.ParentChildHtmlChunker
import shuman.bsu.famcs.rag.knowledge.chunking.implementation.RecursiveCharacterSplitter
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties.AnalyzerType
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.embedding.implementation.OnnxEmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.indexing.Indexer
import shuman.bsu.famcs.rag.knowledge.indexing.job.IndexingJob
import shuman.bsu.famcs.rag.knowledge.indexing.repository.IndexRunRepository
import shuman.bsu.famcs.rag.knowledge.indexing.repository.SourceDocumentRepository
import shuman.bsu.famcs.rag.knowledge.indexing.repository.jdbc.IndexRunRepositoryJdbcImpl
import shuman.bsu.famcs.rag.knowledge.indexing.repository.jdbc.SourceDocumentRepositoryJdbcImpl
import shuman.bsu.famcs.rag.knowledge.onnx.OnnxSession
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.rerank.implementation.OnnxCrossEncoderReranker
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.source.DataSource
import shuman.bsu.famcs.rag.knowledge.source.PageFetcher
import shuman.bsu.famcs.rag.knowledge.source.implementation.HttpPageFetcher
import shuman.bsu.famcs.rag.knowledge.source.implementation.SiteCrawler
import shuman.bsu.famcs.rag.knowledge.source.implementation.TlsContexts
import shuman.bsu.famcs.rag.knowledge.source.implementation.WebsiteDataSource
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.TextAnalyzer
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.FastEmbedCompatibleAnalyzer
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.RussianTextAnalyzer
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseWriter
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeCollections
import shuman.bsu.famcs.rag.knowledge.store.implementation.QdrantCollections
import shuman.bsu.famcs.rag.knowledge.store.implementation.QdrantKnowledgeReader
import shuman.bsu.famcs.rag.knowledge.store.implementation.QdrantKnowledgeWriter
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import shuman.bsu.famcs.rag.platform.runtime.AppDispatchers
import shuman.bsu.famcs.rag.platform.runtime.ApplicationScope
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

    /** Сессия ONNX с моделью эмбеддингов; закрывается при остановке приложения. */
    @Bean(destroyMethod = "close")
    fun embeddingModel(properties: KnowledgeProperties): OnnxSession = properties.embedding.let {
        OnnxSession(it.modelPath, it.tokenizerPath, maxTokens = it.maxTokens, intraOpThreads = it.intraOpThreads)
    }

    /** Вычисление смысловых векторов (bge-m3 на процессоре). */
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

    /** Сессия ONNX с моделью реранкера; закрывается при остановке приложения. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty("bot.knowledge.reranker.enabled", havingValue = "true")
    fun rerankerModel(properties: KnowledgeProperties): OnnxSession = properties.reranker.let {
        OnnxSession(it.modelPath, it.tokenizerPath, maxTokens = it.maxTokens, intraOpThreads = it.intraOpThreads)
    }

    /** Реранкер: кросс-энкодер переоценивает найденные разделы. Бин не создаётся, если реранкер выключен в настройках. */
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

    /** Анализатор текста для поиска по словам: английский как в Python-версии либо русский (настройка `sparse.analyzer`). */
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

    /** Кодировщик BM25: превращает текст в разреженный вектор термов. */
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

    /** Клиент Qdrant по gRPC; закрывается при остановке приложения. */
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

    /** Управление коллекциями Qdrant: создание, алиас на активную, переключение и удаление старых. */
    @Bean
    fun knowledgeCollections(
        client: QdrantClient,
        properties: KnowledgeProperties,
        clock: Clock,
    ): KnowledgeCollections = QdrantCollections(client, properties.qdrant, properties.sparse, properties.embedding.dimension, clock)

    /** Чтение из активной коллекции: гибридный поиск. */
    @Bean
    fun knowledgeReader(
        client: QdrantClient,
        properties: KnowledgeProperties,
    ): KnowledgeBaseReader = QdrantKnowledgeReader(client, properties.qdrant, properties.search)

    /** Запись в коллекцию: замена и удаление документов. */
    @Bean
    fun knowledgeWriter(
        client: QdrantClient,
        properties: KnowledgeProperties,
        collections: KnowledgeCollections,
    ): KnowledgeBaseWriter = QdrantKnowledgeWriter(client, properties.qdrant, collections)

    // --- Поиск знаний по вопросу ---

    /** Поиск знаний по вопросу: гибридный поиск, родители, реранкер. */
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

    /** Нарезка HTML-страницы: Markdown, разделы по заголовкам, мелкие фрагменты для поиска. */
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
            maxParentChars = chunking.parentMaxChars,
        )
    }

    /** Скачивание страниц по HTTP с дополнительными сертификатами для сайта факультета. */
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

    /** Источник данных «сайт факультета»: обход ссылок и загрузка страниц. */
    @Bean
    fun websiteDataSource(
        fetcher: PageFetcher,
        properties: KnowledgeProperties,
    ): DataSource = WebsiteDataSource(SiteCrawler(fetcher, properties.crawler), fetcher)

    /** Учёт проиндексированных страниц: адрес, хеш, версия. */
    @Bean
    fun sourceDocumentRepository(database: Database): SourceDocumentRepository = SourceDocumentRepositoryJdbcImpl(database)

    /** Журнал запусков индексации. */
    @Bean
    fun indexRunRepository(database: Database): IndexRunRepository = IndexRunRepositoryJdbcImpl(database)

    /** Индексатор: обновление базы знаний из источников. */
    @Bean
    fun indexer(
        sources: List<DataSource>,
        chunkers: List<Chunker>,
        embeddings: EmbeddingProvider,
        sparse: Bm25SparseEncoder,
        writer: KnowledgeBaseWriter,
        collections: KnowledgeCollections,
        documents: SourceDocumentRepository,
        runs: IndexRunRepository,
        properties: KnowledgeProperties,
        clock: Clock,
        meters: MeterRegistry,
    ) = Indexer(sources, chunkers, embeddings, sparse, writer, collections, documents, runs, properties.indexing, clock, meters)

    /** Фоновый запуск индексации: не больше одного запуска за раз, итог администраторам. */
    @Bean
    fun indexingJob(
        indexer: Indexer,
        scope: ApplicationScope,
        notifier: AdminNotifier,
    ) = IndexingJob(indexer, scope, notifier)
}
