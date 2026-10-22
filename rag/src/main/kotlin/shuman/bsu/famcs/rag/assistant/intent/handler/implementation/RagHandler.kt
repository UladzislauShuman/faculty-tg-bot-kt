package shuman.bsu.famcs.rag.assistant.intent.handler.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.api.model.AnswerSource
import shuman.bsu.famcs.rag.assistant.configuration.properties.RagHandlerProperties
import shuman.bsu.famcs.rag.assistant.intent.generator.AnswerGenerator
import shuman.bsu.famcs.rag.assistant.intent.handler.IntentHandler
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerInput
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.knowledge.model.PageType
import shuman.bsu.famcs.rag.knowledge.model.RetrievedCandidate
import shuman.bsu.famcs.rag.knowledge.model.RetrievedPassage
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import java.time.ZoneId
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * Ветка RAG: поиск по базе знаний и ответ модели строго по найденным разделам.
 *
 * Порядок как в baseline: поиск идёт по переформулированному запросу, а генератор получает исходный вопрос пользователя,
 * историю и найденные разделы.
 * Если поиск ничего не нашёл, модель не вызывается: ответ «не нашёл» не стоит токенов.
 * Поиск и генерация вызываются через отдельные объекты, поэтому проверяются по отдельности.
 */
class RagHandler(
    private val retriever: KnowledgeRetriever,
    private val generator: AnswerGenerator,
    private val properties: RagHandlerProperties,
    private val noInfoText: String,
    private val zone: ZoneId,
) : IntentHandler {
    override val intent = Intent.RAG

    override suspend fun handle(input: HandlerInput): HandlerOutput {
        val retrieval = retriever.retrieve(input.query)
        log.debug { "Поиск: разделов ${retrieval.passages.size} из ${retrieval.candidates}, лучшая оценка ${retrieval.bestRelevance}" }
        if (retrieval.passages.isEmpty()) {
            return HandlerOutput(text = noInfoText, noInfo = true, candidates = retrieval.pool, searchMillis = retrieval.searchMillis)
        }

        val generationStart = TimeSource.Monotonic.markNow()
        val answer = generator.generate(input.question, input.context, retrieval.passages)
        val suggestions = if (answer.noInfo) suggestionsOf(retrieval.pool) else emptyList()
        return HandlerOutput(
            text = answer.text,
            noInfo = answer.noInfo,
            sources = if (answer.noInfo) emptyList() else sourcesOf(retrieval.passages),
            provider = answer.provider,
            model = answer.model,
            passages = retrieval.passages,
            candidates = retrieval.pool,
            searchMillis = retrieval.searchMillis,
            rerankMillis = retrieval.rerankMillis,
            generationMillis = generationStart.elapsedNow().inWholeMilliseconds,
            suggestions = suggestions,
        )
    }

    /**
     * Близкие страницы для ответа «не нашёл»: из кандидатов поиска без перечней, с оценкой не ниже порога, по одной на страницу.
     * Точного ответа нет, но страница по теме (олимпиады, госэкзамен) подсказывает, где искать.
     *
     * @param pool кандидаты поиска в порядке реранкера.
     */
    private fun suggestionsOf(pool: List<RetrievedCandidate>): List<AnswerSource> = pool
        .filter { it.pageType != PageType.LIST && ((it.relevance ?: 1.0) * it.weight) >= properties.suggestionMinScore }
        .distinctBy { it.url }
        .take(properties.maxSuggestions)
        .map { AnswerSource(url = it.url, title = it.title.ifBlank { null }, checkedAt = null) }

    /**
     * Выбирает источники для показа: по одной ссылке на страницу, не больше `maxSources`, с датой проверки страницы.
     *
     * @param passages найденные разделы.
     */
    private fun sourcesOf(passages: List<RetrievedPassage>): List<AnswerSource> = passages
        .distinctBy { it.url }
        .take(properties.maxSources)
        .map { AnswerSource(url = it.url, title = it.title.ifBlank { null }, checkedAt = it.indexedAt?.atZone(zone)?.toLocalDate()) }
}
