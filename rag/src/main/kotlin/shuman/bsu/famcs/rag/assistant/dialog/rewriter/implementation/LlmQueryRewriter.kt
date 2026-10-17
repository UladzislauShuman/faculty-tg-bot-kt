package shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.QueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.service.toMessages
import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Переформулирование вопроса моделью с ролью REWRITER: «а там какой балл?» превращается в самостоятельный запрос.
 * При любом сбое модели возвращается исходный вопрос: поиск ухудшится, но ответ будет.
 *
 * @property llm клиент моделей.
 * @property prompts библиотека промптов.
 * @property onlyWithHistory переформулировать только при наличии истории.
 * @property maxChars предельная длина результата.
 */
class LlmQueryRewriter(
    private val llm: LlmClient,
    private val prompts: PromptLibrary,
    /** `true` — переформулировать, только когда есть история (первый вопрос уже самостоятелен и не стоит токенов). */
    private val onlyWithHistory: Boolean,
    /** Ответ длиннее считается сбоем: модель начала отвечать на вопрос вместо переформулировки. */
    private val maxChars: Int,
) : QueryRewriter {
    override suspend fun rewrite(
        question: String,
        context: DialogContext,
    ): String {
        if (onlyWithHistory && !context.hasMemory) return question

        val messages = context.toMessages(prompts.render("rewrite-system"), question)
        val rewritten = try {
            llm.complete(LlmRequest(LlmRole.REWRITER, messages)).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmUnavailableException) {
            log.warn { "Переформулировка не удалась, ищем по исходному вопросу: ${e.message}" }
            return question
        }
        return clean(rewritten) ?: question.also { log.warn { "Переформулировка отброшена: пустой или слишком длинный ответ модели" } }
    }

    /** Убирает кавычки и пробелы вокруг ответа. `null`, если ответ пустой или похож на развёрнутый ответ вместо запроса. */
    private fun clean(raw: String): String? {
        val text = raw.trim().trim('"', '\'', '«', '»').trim()
        return text.takeIf { it.isNotEmpty() && it.length <= maxChars }
    }
}
