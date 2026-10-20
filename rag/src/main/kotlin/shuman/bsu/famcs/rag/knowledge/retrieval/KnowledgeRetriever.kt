package shuman.bsu.famcs.rag.knowledge.retrieval

import io.micrometer.core.instrument.MeterRegistry
import shuman.bsu.famcs.rag.knowledge.chunking.ChunkContext
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.model.RetrievalResult
import shuman.bsu.famcs.rag.knowledge.model.RetrievedCandidate
import shuman.bsu.famcs.rag.knowledge.model.RetrievedPassage
import shuman.bsu.famcs.rag.knowledge.model.SearchHit
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.platform.metrics.timed
import kotlin.time.TimeSource

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
    /** Поправка порядка по типу страницы и году вопроса; по умолчанию без поправок. */
    private val ranking: RankingPolicy = RankingPolicy.NEUTRAL,
    /** Подавать реранкеру название страницы и раздела вместе с текстом раздела. */
    private val rerankWithContext: Boolean = false,
) {
    /**
     * Ищет разделы базы знаний для запроса: гибридный поиск (смысл и слова) по фрагментам, переход к разделам-родителям
     * и переранжирование кросс-энкодером. Время каждого этапа пишется в метрику `retrieval.latency`.
     *
     * @param query самостоятельный запрос.
     * @return лучшие разделы с оценками; пустой список, если ничего не нашлось.
     */
    suspend fun retrieve(query: String): RetrievalResult {
        val searchStart = TimeSource.Monotonic.markNow()
        val hits = meters.timed("retrieval.latency", "stage", "search") {
            reader.search(HybridSearchRequest(dense = embeddings.embedQuery(query), sparse = sparse.encodeQuery(query)))
        }
        val searchMillis = searchStart.elapsedNow().inWholeMilliseconds
        val parents = uniqueParents(hits)
        if (parents.isEmpty()) return RetrievalResult(emptyList(), candidates = 0, searchMillis = searchMillis)

        if (reranker == null) {
            val selected = parents.take(topN)
            val pool = parents.map { it.toCandidate(relevance = null, selected = it in selected) }
            return RetrievalResult(selected.map { it.toPassage(relevance = null) }, parents.size, pool, searchMillis)
        }
        val rerankStart = TimeSource.Monotonic.markNow()
        val scores = meters.timed("retrieval.latency", "stage", "rerank") { reranker.score(query, parents.map { rerankText(it) }) }
        val rerankMillis = rerankStart.elapsedNow().inWholeMilliseconds
        val years = RankingPolicy.yearsIn(query)
        val weights = parents.associateWith { ranking.factor(it.pageType, it.year, years) }
        // Порядок задаёт оценка реранкера с поправкой по типу страницы; в трассе остаётся и сама оценка, и множитель.
        val ranked = parents.zip(scores).sortedByDescending { (hit, score) -> score * weights.getValue(hit) }
        val selected = ranked.take(topN).map { it.first }.toSet()
        val pool = ranked.map { (hit, score) -> hit.toCandidate(score, selected = hit in selected, weight = weights.getValue(hit)) }
        return RetrievalResult(
            ranked.take(topN).map { (hit, score) ->
                hit.toPassage(score)
            },
            parents.size,
            pool,
            searchMillis,
            rerankMillis,
        )
    }

    /** Кандидат для разбора поиска: раздел с оценкой, длиной и началом фрагмента, по которому он найден. */
    private fun SearchHit.toCandidate(
        relevance: Double?,
        selected: Boolean,
        weight: Double = 1.0,
    ) = RetrievedCandidate(
        parentId = parentId,
        url = url,
        title = title,
        relevance = relevance,
        parentChars = parentText.length,
        matchedFragment = text.take(MATCHED_PREVIEW),
        selected = selected,
        pageType = pageType,
        weight = weight,
    )

    /** Текст, который оценивает реранкер: раздел, а при включённой настройке с названием страницы и заголовком раздела впереди. */
    private fun rerankText(hit: SearchHit): String =
        if (rerankWithContext) "${ChunkContext.line(hit.title, hit.headings)}\n${hit.parentText}" else hit.parentText

    private companion object {
        /** Сколько знаков найденного фрагмента показывается в трассе. */
        const val MATCHED_PREVIEW = 160
    }

    /** Несколько фрагментов одного раздела дают один раздел. Порядок поиска сохраняется. */
    private fun uniqueParents(hits: List<SearchHit>): List<SearchHit> = hits.distinctBy { it.parentId.ifEmpty { it.id } }

    /**
     * Переводит найденный фрагмент в раздел для промпта: берётся текст родителя, а не мелкого фрагмента.
     *
     * @param relevance оценка реранкера или `null`, если реранкер не применялся.
     */
    private fun SearchHit.toPassage(relevance: Double?) = RetrievedPassage(
        parentId = parentId,
        text = parentText,
        url = url,
        title = title,
        headings = headings,
        indexedAt = indexedAt,
        relevance = relevance,
        pageType = pageType,
        year = year,
    )
}
