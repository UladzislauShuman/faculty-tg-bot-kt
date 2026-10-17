package shuman.bsu.famcs.rag.assistant.dialog.service

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.assistant.dialog.model.Author
import shuman.bsu.famcs.rag.assistant.dialog.model.StoredMessage
import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Сжимает старую часть диалога в краткую сводку.
 *
 * Сводка дополняется: на вход идут прежняя сводка и только новые сообщения, поэтому стоимость вызова не растёт с длиной диалога.
 * Сбой не критичен: сводка не получится сейчас, получится при следующем сообщении, а ответ пользователю не страдает.
 */
class ConversationSummarizer(
    private val llm: LlmClient,
    private val prompts: PromptLibrary,
    /** Ограничение длины сводки, символы. Передаётся модели в промпте. */
    private val maxChars: Int,
) {
    /** Возвращает новую сводку или `null`, если модель недоступна или вернула мусор. */
    suspend fun summarize(
        previousSummary: String?,
        messages: List<StoredMessage>,
    ): String? {
        if (messages.isEmpty()) return previousSummary
        val dialogue = buildString {
            if (!previousSummary.isNullOrBlank()) appendLine("Сводка предыдущей части: $previousSummary")
            messages.forEach { appendLine("${if (it.author == Author.USER) "Пользователь" else "Бот"}: ${it.text}") }
        }.trimEnd()

        val prompt = prompts.render("summary", "dialogue" to dialogue, "max_chars" to maxChars.toString())
        return try {
            llm
                .complete(LlmRequest(LlmRole.SUMMARIZER, listOf(LlmMessage.user(prompt))))
                .text
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmUnavailableException) {
            log.warn { "Сводка диалога не получена: ${e.message}" }
            null
        }
    }
}
