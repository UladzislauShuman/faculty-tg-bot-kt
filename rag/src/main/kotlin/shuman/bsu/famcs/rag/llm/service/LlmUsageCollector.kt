package shuman.bsu.famcs.rag.llm.service

import shuman.bsu.famcs.rag.llm.model.TokenUsage
import shuman.bsu.famcs.rag.llm.service.implementation.ResilientLlmClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Собирает расход токенов всех вызовов модели в рамках одного сообщения пользователя.
 *
 * Кладётся в контекст корутины обработки (`withContext(LlmUsageCollector())`); [ResilientLlmClient] добавляет в него расход
 * каждого вызова. Так итог по сообщению (для журнала и суточных лимитов) считается без передачи счётчика через сигнатуры шагов.
 * Потокобезопасен: шаги одного сообщения могут идти параллельно.
 */
class LlmUsageCollector : AbstractCoroutineContextElement(Key) {
    private val input = AtomicInteger()
    private val output = AtomicInteger()

    val total: TokenUsage get() = TokenUsage(input.get(), output.get())

    /**
     * Добавляет расход токенов одного вызова модели.
     *
     * @param usage токены, потраченные вызовом.
     */
    fun add(usage: TokenUsage) {
        input.addAndGet(usage.inputTokens)
        output.addAndGet(usage.outputTokens)
    }

    companion object Key : CoroutineContext.Key<LlmUsageCollector>
}
