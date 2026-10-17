package shuman.bsu.famcs.rag.llm.provider

import shuman.bsu.famcs.rag.llm.exception.LlmException
import shuman.bsu.famcs.rag.llm.model.ProviderCall
import shuman.bsu.famcs.rag.llm.model.ProviderReply
import shuman.bsu.famcs.rag.llm.service.implementation.ResilientLlmClient

/**
 * Один бэкенд языковой модели (Ollama, YandexGPT, GigaChat, DeepSeek…).
 *
 * Провайдер делает ровно одно: отправляет запрос и переводит ошибки в [LlmException].
 * Повторы, резерв, таймаут и лимит одновременных вызовов — забота [ResilientLlmClient], не провайдера.
 */
interface LlmProvider {
    /** Имя из конфигурации (`bot.llm.providers.<имя>`), по нему провайдера выбирают в цепочках ролей. */
    val name: String

    /**
     * Отправляет сообщения модели и возвращает её ответ.
     *
     * @param call запрос: модель, сообщения, параметры генерации.
     * @return текст ответа и расход токенов, если провайдер его сообщил.
     * @throws shuman.bsu.famcs.rag.llm.exception.LlmException ошибка провайдера, приведённая к типам слоя LLM.
     */
    suspend fun chat(call: ProviderCall): ProviderReply
}
