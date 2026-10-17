package shuman.bsu.famcs.rag.llm

/**
 * Один бэкенд языковой модели (Ollama, YandexGPT, GigaChat, DeepSeek…).
 *
 * Провайдер делает ровно одно: отправляет запрос и переводит ошибки в [LlmException].
 * Повторы, резерв, таймаут и лимит одновременных вызовов — забота [ResilientLlmClient], не провайдера.
 */
interface LlmProvider {
    /** Имя из конфигурации (`bot.llm.providers.<имя>`), по нему провайдера выбирают в цепочках ролей. */
    val name: String

    suspend fun chat(call: ProviderCall): ProviderReply
}

/** Что отправляется провайдеру: модель и параметры уже выбраны по настройкам роли. */
data class ProviderCall(
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double,
    val topP: Double,
    val maxOutputTokens: Int,
)

/** Что провайдер вернул. `usage == null`, если провайдер не сообщил расход токенов. */
data class ProviderReply(
    val text: String,
    val usage: TokenUsage?,
    val model: String,
)
