package shuman.bsu.famcs.rag.assistant.intent.handler

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.assistant.configuration.properties.RagHandlerProperties
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.intent.generator.AnswerGenerator
import shuman.bsu.famcs.rag.assistant.intent.generator.model.GeneratedAnswer
import shuman.bsu.famcs.rag.assistant.intent.handler.implementation.RagHandler
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerInput
import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.model.PageType
import shuman.bsu.famcs.rag.knowledge.model.SearchHit
import shuman.bsu.famcs.rag.knowledge.rerank.Reranker
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.retrieval.RankingPolicy
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.sparse.implementation.RussianTextAnalyzer
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.platform.identifier.SessionId
import shuman.bsu.famcs.rag.testsupport.HashEmbeddingProvider
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверка «возможно, поможет»: при ответе «не нашёл» предлагаются близкие страницы, но не перечни и не мусор. */
class RagHandlerSuggestionsTest {
    private val context = DialogContext(SessionId(1), summary = null, history = emptyList())

    private fun hit(
        id: String,
        type: PageType = PageType.SECTION,
    ) = SearchHit(
        id,
        0.5,
        "фрагмент $id",
        id,
        "текст раздела $id",
        "https://fpmi.bsu.by/$id",
        "Страница $id | Факультет",
        emptyMap(),
        null,
        type,
    )

    private fun handler(
        scores: List<Double>,
        noInfo: Boolean,
        properties: RagHandlerProperties = RagHandlerProperties("нет точной информации", 3, maxSuggestions = 2, suggestionMinScore = 0.05),
    ): RagHandler {
        val hits = listOf(hit("olympiads"), hit("list", PageType.LIST), hit("exam"), hit("trash"))
        val reader = object : KnowledgeBaseReader {
            override suspend fun search(request: HybridSearchRequest) = hits

            override suspend fun chunkCount() = hits.size.toLong()
        }
        val reranker = object : Reranker {
            override suspend fun score(
                query: String,
                passages: List<String>,
            ) = scores
        }
        val retriever = KnowledgeRetriever(
            embeddings = HashEmbeddingProvider(),
            sparse = Bm25SparseEncoder(RussianTextAnalyzer(40), 1.2, 0.75, 256.0),
            reader = reader,
            reranker = reranker,
            topN = 3,
            meters = SimpleMeterRegistry(),
            ranking = RankingPolicy(mapOf(PageType.LIST to 0.25)),
        )
        val generator = AnswerGenerator { _, _, _ -> GeneratedAnswer("ответ", noInfo, "p", "m") }
        return RagHandler(retriever, generator, properties, "нет", ZoneOffset.UTC)
    }

    private suspend fun RagHandler.ask() = handle(HandlerInput("Что с олимпиадами?", "Что с олимпиадами?", context))

    @Test
    fun `при не нашёл предлагаются лучшие страницы без перечней и с оценкой выше порога`() = runTest {
        val output = handler(scores = listOf(0.4, 0.9, 0.2, 0.01), noInfo = true).ask()

        assertEquals(
            listOf("https://fpmi.bsu.by/olympiads", "https://fpmi.bsu.by/exam"),
            output.suggestions.map {
                it.url
            },
            "перечень (0,9 × 0,25 < 0,4) ниже, мусор отсечён порогом, всего две",
        )
        assertTrue(output.sources.isEmpty(), "источники ответа «не нашёл» по-прежнему не показываются")
    }

    @Test
    fun `у обычного ответа предложений нет, при нулевом лимите их тоже нет`() = runTest {
        assertTrue(handler(listOf(0.4, 0.9, 0.2, 0.01), noInfo = false).ask().suggestions.isEmpty())
        val off = handler(listOf(0.4, 0.9, 0.2, 0.01), noInfo = true, properties = RagHandlerProperties("нет точной информации", 3)).ask()
        assertTrue(off.suggestions.isEmpty())
    }
}
