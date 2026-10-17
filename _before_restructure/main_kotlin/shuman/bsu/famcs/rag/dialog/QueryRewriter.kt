package shuman.bsu.famcs.rag.dialog

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.llm.LlmClient
import shuman.bsu.famcs.rag.llm.LlmRequest
import shuman.bsu.famcs.rag.llm.LlmRole
import shuman.bsu.famcs.rag.llm.LlmUnavailableException
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Превращает вопрос в самостоятельный запрос для поиска.
 *
 * Зачем: «А какой там проходной балл?» без истории ничего не значит ни для векторного поиска, ни для классификатора.
 * Переформулировка стоит перед классификатором (решение автора): так он видит самостоятельный вопрос.
 */
fun interface QueryRewriter {
    /** Возвращает самостоятельную формулировку. При любом сбое возвращает исходный вопрос: поиск ухудшится, но ответ будет. */
    suspend fun rewrite(
        question: String,
        context: DialogContext,
    ): String
}

/** Переформулирование выключено (`mode: OFF`): вопрос идёт в поиск как есть. */
object IdentityQueryRewriter : QueryRewriter {
    override suspend fun rewrite(
        question: String,
        context: DialogContext,
    ) = question
}

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
