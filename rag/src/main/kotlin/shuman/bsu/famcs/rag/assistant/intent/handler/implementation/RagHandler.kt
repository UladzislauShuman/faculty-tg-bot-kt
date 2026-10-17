package shuman.bsu.famcs.rag.assistant.intent.handler.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.api.model.AnswerSource
import shuman.bsu.famcs.rag.assistant.configuration.properties.RagHandlerProperties
import shuman.bsu.famcs.rag.assistant.dialog.service.toMessages
import shuman.bsu.famcs.rag.assistant.intent.handler.IntentHandler
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerInput
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.knowledge.model.RetrievedPassage
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import java.time.ZoneId

private val log = KotlinLogging.logger {}

/**
 * Ветка RAG: поиск по базе знаний и ответ модели строго по найденным разделам.
 *
 * Порядок как в baseline: поиск идёт по переформулированному запросу, а модель получает исходный вопрос пользователя,
 * историю и найденные разделы (разделы склеены через пустую строку).
 * Если поиск ничего не нашёл, модель не вызывается: ответ «не нашёл» не стоит токенов.
 */
class RagHandler(
    private val retriever: KnowledgeRetriever,
    private val llm: LlmClient,
    private val prompts: PromptLibrary,
    private val properties: RagHandlerProperties,
    private val noInfoText: String,
    private val zone: ZoneId,
) : IntentHandler {
    override val intent = Intent.RAG

    init {
        // Если фразу в промпте поменяли, а в конфиге нет, «не нашёл» перестало бы распознаваться и под ним показывались бы источники.
        require(normalize(properties.noInfoMarker) in normalize(prompts.render("answer-system"))) {
            "bot.rag.no-info-marker не найден в prompts/answer-system.txt: приведите фразы к одному виду"
        }
    }

    override suspend fun handle(input: HandlerInput): HandlerOutput {
        val retrieval = retriever.retrieve(input.query)
        log.debug { "Поиск: разделов ${retrieval.passages.size} из ${retrieval.candidates}, лучшая оценка ${retrieval.bestRelevance}" }
        if (retrieval.passages.isEmpty()) return HandlerOutput(text = noInfoText, noInfo = true)

        val system = prompts.render("answer-system", "context" to retrieval.passages.joinToString("\n\n") { it.text })
        val human = prompts.render("answer-human", "input" to input.question)
        val reply = llm.complete(LlmRequest(LlmRole.GENERATOR, input.context.toMessages(system, human)))

        val noInfo = normalize(properties.noInfoMarker) in normalize(reply.text)
        return HandlerOutput(
            text = reply.text.trim(),
            noInfo = noInfo,
            sources = if (noInfo) emptyList() else sourcesOf(retrieval.passages),
            provider = reply.provider,
            model = reply.model,
        )
    }

    /**
     * Выбирает источники для показа: по одной ссылке на страницу, не больше `maxSources`, с датой проверки страницы.
     *
     * @param passages найденные разделы.
     */
    private fun sourcesOf(passages: List<RetrievedPassage>): List<AnswerSource> = passages
        .distinctBy { it.url }
        .take(properties.maxSources)
        .map { AnswerSource(url = it.url, title = it.title.ifBlank { null }, checkedAt = it.indexedAt?.atZone(zone)?.toLocalDate()) }

    /** Приводит текст к виду для сравнения: нижний регистр, «ё» как «е», пробелы схлопнуты. */
    private fun normalize(text: String) = text
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()
}
