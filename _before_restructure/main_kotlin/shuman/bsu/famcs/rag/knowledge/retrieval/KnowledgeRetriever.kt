package shuman.bsu.famcs.rag.knowledge.retrieval

import io.micrometer.core.instrument.MeterRegistry
import shuman.bsu.famcs.rag.common.timed
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.store.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.knowledge.store.SearchHit
import java.time.Instant

/** Раздел документа, найденный по вопросу: этот текст уходит в промпт как контекст. */
data class RetrievedPassage(
    val parentId: String,
    val text: String,
    val url: String,
    val title: String,
    val headings: Map<String, String>,
    /** Когда страница в последний раз проверялась индексатором. */
    val indexedAt: Instant?,
    /** Оценка реранкера от 0 до 1. `null`, если реранкер выключен. */
    val relevance: Double?,
)

/** Что нашлось по вопросу. */
data class RetrievalResult(
    val passages: List<RetrievedPassage>,
    /** Сколько разделов было до реранкера. Нужно для метрик и диагностики. */
    val candidates: Int,
) {
    /** Лучшая оценка среди найденного. По ней решается, есть ли в базе ответ. */
    val bestRelevance: Double? get() = passages.mapNotNull { it.relevance }.maxOrNull()
}

/**
 * Поиск знаний по вопросу: от текста запроса до трёх лучших разделов.
 *
 * Порядок как в baseline: два вектора запроса (по смыслу и по словам) → гибридный поиск в Qdrant (десять фрагментов) →
 * замена фрагментов их родительскими разделами без повторов → реранкер оставляет лучшие [topN].
 * Реранкер оценивает именно родительские разделы, потому что именно их увидит модель.
 */
class KnowledgeRetriever(
    private val embeddings: EmbeddingProvider,
    private val sparse: Bm25SparseEncoder,
    private val reader: KnowledgeBaseReader,
    /** `null` — реранкер выключен: берутся первые [topN] разделов в порядке поиска. */
    private val reranker: Reranker?,
    private val topN: Int,
    private val meters: MeterRegistry,
) {
    suspend fun retrieve(query: String): RetrievalResult {
        val hits = meters.timed("retrieval.latency", "stage", "search") {
            reader.search(HybridSearchRequest(dense = embeddings.embedQuery(query), sparse = sparse.encodeQuery(query)))
        }
        val parents = uniqueParents(hits)
        if (parents.isEmpty()) return RetrievalResult(emptyList(), candidates = 0)

        if (reranker == null) {
            return RetrievalResult(parents.take(topN).map { it.toPassage(relevance = null) }, parents.size)
        }
        val scores = meters.timed("retrieval.latency", "stage", "rerank") { reranker.score(query, parents.map { it.parentText }) }
        val ranked = parents.zip(scores).sortedByDescending { (_, score) -> score }.take(topN)
        return RetrievalResult(ranked.map { (hit, score) -> hit.toPassage(score) }, parents.size)
    }

    /** Несколько фрагментов одного раздела дают один раздел. Порядок поиска сохраняется. */
    private fun uniqueParents(hits: List<SearchHit>): List<SearchHit> = hits.distinctBy { it.parentId.ifEmpty { it.id } }

    private fun SearchHit.toPassage(relevance: Double?) = RetrievedPassage(
        parentId = parentId,
        text = parentText,
        url = url,
        title = title,
        headings = headings,
        indexedAt = indexedAt,
        relevance = relevance,
    )
}
