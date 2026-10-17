package shuman.bsu.famcs.rag.intent

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.core.AnswerSource
import shuman.bsu.famcs.rag.dialog.toMessages
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.knowledge.retrieval.RetrievedPassage
import shuman.bsu.famcs.rag.llm.LlmClient
import shuman.bsu.famcs.rag.llm.LlmRequest
import shuman.bsu.famcs.rag.llm.LlmRole
import java.time.ZoneId

private val log = KotlinLogging.logger {}

/** Настройки ветки RAG (`bot.rag`). */
@Validated
@ConfigurationProperties("bot.rag")
data class RagProperties(
    /**
     * Фраза, по которой ответ модели считается «не нашёл». Должна совпадать с фразой из `prompts/answer-system.txt`:
     * при расхождении приложение не запустится. Сравнение без учёта регистра и лишних пробелов.
     */
    @field:NotBlank val noInfoMarker: String,
    /** Сколько источников показывать под ответом. */
    @field:Positive val maxSources: Int,
)

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
    private val properties: RagProperties,
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

    private fun sourcesOf(passages: List<RetrievedPassage>): List<AnswerSource> = passages
        .distinctBy { it.url }
        .take(properties.maxSources)
        .map { AnswerSource(url = it.url, title = it.title.ifBlank { null }, checkedAt = it.indexedAt?.atZone(zone)?.toLocalDate()) }

    private fun normalize(text: String) = text
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()
}
