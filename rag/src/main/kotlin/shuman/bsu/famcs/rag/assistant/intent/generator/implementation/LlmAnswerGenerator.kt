package shuman.bsu.famcs.rag.assistant.intent.generator.implementation

import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.dialog.service.toMessages
import shuman.bsu.famcs.rag.assistant.intent.generator.AnswerGenerator
import shuman.bsu.famcs.rag.assistant.intent.generator.model.GeneratedAnswer
import shuman.bsu.famcs.rag.knowledge.chunking.ChunkContext
import shuman.bsu.famcs.rag.knowledge.model.RetrievedPassage
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary

/**
 * Генерация ответа моделью с ролью GENERATOR.
 *
 * Разделы склеиваются через пустую строку и подставляются в системный промпт, исходный вопрос идёт последним сообщением.
 * Признак «не нашёл» берётся по фразе из промпта: при расхождении фразы в промпте и в конфиге приложение не запустится.
 *
 * @property llm клиент моделей.
 * @property prompts библиотека промптов.
 * @property noInfoMarker фраза, по которой ответ считается «не нашёл».
 */
class LlmAnswerGenerator(
    private val llm: LlmClient,
    private val prompts: PromptLibrary,
    /** Должна совпадать с фразой из `prompts/answer-system.txt`. Сравнение без учёта регистра и лишних пробелов. */
    private val noInfoMarker: String,
    /** Подписывать каждый раздел: «[Источник: название страницы, год]». */
    private val passageHeader: Boolean = false,
) : AnswerGenerator {
    init {
        // Если фразу в промпте поменяли, а в конфиге нет, «не нашёл» перестало бы распознаваться и под ним показывались бы источники.
        require(normalize(noInfoMarker) in normalize(prompts.render("answer-system"))) {
            "bot.rag.no-info-marker не найден в prompts/answer-system.txt: приведите фразы к одному виду"
        }
    }

    override suspend fun generate(
        question: String,
        context: DialogContext,
        passages: List<RetrievedPassage>,
    ): GeneratedAnswer {
        val system = prompts.render("answer-system", "context" to passages.joinToString("\n\n") { withHeader(it) })
        val human = prompts.render("answer-human", "input" to question)
        val reply = llm.complete(LlmRequest(LlmRole.GENERATOR, context.toMessages(system, human)))
        return GeneratedAnswer(
            text = reply.text.trim(),
            noInfo = normalize(noInfoMarker) in normalize(reply.text),
            provider = reply.provider,
            model = reply.model,
        )
    }

    /** Текст раздела для промпта; при включённой подписи впереди название страницы и год. */
    private fun withHeader(passage: RetrievedPassage): String {
        if (!passageHeader) return passage.text
        val year = passage.year?.let { ", $it" }.orEmpty()
        return "[Источник: ${ChunkContext.shortTitle(passage.title)}$year]\n${passage.text}"
    }

    /** Приводит текст к виду для сравнения: нижний регистр, «ё» как «е», пробелы схлопнуты. */
    private fun normalize(text: String) = text
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()
}
