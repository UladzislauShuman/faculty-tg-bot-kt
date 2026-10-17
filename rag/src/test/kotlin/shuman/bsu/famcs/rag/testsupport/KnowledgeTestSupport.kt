package shuman.bsu.famcs.rag.testsupport

import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.model.IndexedChunk
import shuman.bsu.famcs.rag.knowledge.model.IndexedDocument
import shuman.bsu.famcs.rag.knowledge.model.SparseVector
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Настройки модуля знаний для тестов с настоящим Qdrant. Размерность вектора маленькая: тестам нужны не модели, а схема данных. */
object TestKnowledge {
    const val DIMENSION = 4

    fun qdrant(
        host: String,
        port: Int,
        alias: String = "test_kb",
    ) = KnowledgeProperties.Qdrant(
        host = host,
        grpcPort = port,
        useTls = false,
        apiKey = null,
        collectionAlias = alias,
        denseVectorName = "dense",
        sparseVectorName = "sparse",
        upsertBatchSize = 2,
        requestTimeout = Duration.ofSeconds(30),
        hnswM = 16,
        hnswEfConstruct = 100,
        keepOldCollections = 1,
    )

    val sparse = KnowledgeProperties.Sparse(
        analyzer = KnowledgeProperties.AnalyzerType.FASTEMBED_COMPATIBLE,
        k = 1.2,
        b = 0.75,
        averageLength = 256.0,
        maxTokenLength = 40,
        idfModifier = true,
    )

    val search = KnowledgeProperties.Search(
        candidatesPerLeg = 10,
        resultLimit = 10,
        fusion = KnowledgeProperties.FusionMode.RRF,
        rrfK = 2,
        denseWeight = 0.7f,
        sparseWeight = 0.3f,
        hnswEf = 64,
    )

    fun chunk(
        url: String,
        text: String,
        dense: FloatArray,
        sparseIndices: IntArray,
        parentText: String = "Родительский раздел: $text",
    ) = IndexedChunk(
        id = UUID.nameUUIDFromBytes("$url|$text".toByteArray()).toString(),
        text = text,
        parentId = UUID.nameUUIDFromBytes("$url|parent".toByteArray()).toString(),
        parentText = parentText,
        headings = mapOf("H2" to "Раздел"),
        dense = dense,
        sparse = SparseVector(sparseIndices, FloatArray(sparseIndices.size) { 1.0f }),
    )

    fun document(
        url: String,
        version: Long,
        vararg chunks: IndexedChunk,
    ) = IndexedDocument(
        url = url,
        title = "Страница $url",
        indexedAt = Instant.parse("2026-10-08T03:00:00Z"),
        version = version,
        chunks = chunks.toList(),
    )
}
