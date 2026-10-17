package shuman.bsu.famcs.rag.llm.service

import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.model.TokenUsage

/** Получатель сведений о расходе токенов (суточные счётчики системы). Вызывается после каждого успешного вызова модели. */
fun interface LlmUsageListener {
    /**
     * Вызывается после каждого успешного обращения к модели.
     *
     * @param role роль, для которой вызывалась модель.
     * @param provider имя провайдера.
     * @param model имя модели.
     * @param usage потраченные токены.
     */
    suspend fun onUsage(
        role: LlmRole,
        provider: String,
        model: String,
        usage: TokenUsage,
    )
}
