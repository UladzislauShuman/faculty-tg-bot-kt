package shuman.bsu.famcs.rag.llm.provider

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import shuman.bsu.famcs.rag.llm.configuration.properties.LlmProperties.ProviderProperties
import shuman.bsu.famcs.rag.llm.configuration.properties.LlmProperties.ProviderType
import tools.jackson.databind.json.JsonMapper

/**
 * Собирает [ChatModel] LangChain4j по настройкам провайдера.
 *
 * Принцип проекта: настройки объекта задаются явно, без неявных умолчаний библиотеки. Поэтому здесь перечислены
 * все существенные параметры. Что меняется от роли к роли (температура, лимит ответа), передаётся в каждом вызове.
 */
object ChatModelFactory {
    /**
     * Создаёт клиент модели по типу провайдера.
     *
     * @param providerName имя провайдера в настройках (для сообщений об ошибках).
     * @param properties настройки провайдера.
     * @return клиент LangChain4j для Ollama или OpenAI-совместимого API.
     */
    fun create(
        providerName: String,
        properties: ProviderProperties,
    ): ChatModel = when (properties.type) {
        ProviderType.OLLAMA -> ollama(providerName, properties)
        ProviderType.OPENAI_COMPATIBLE -> openAiCompatible(providerName, properties)
    }

    /**
     * Клиент Ollama. Окно контекста и параметры сэмплирования задаются явно, повторы выключены (их делает наша цепочка).
     *
     * @param providerName имя провайдера.
     * @param properties настройки; блок `ollama` обязателен.
     */
    private fun ollama(
        providerName: String,
        properties: ProviderProperties,
    ): ChatModel {
        val options = requireNotNull(properties.ollama) {
            "Для провайдера '$providerName' (type: OLLAMA) нужен блок bot.llm.providers.$providerName.ollama"
        }
        return OllamaChatModel
            .builder()
            .baseUrl(properties.baseUrl)
            .modelName(properties.defaultModel)
            // Окно контекста Ollama: если не задать, берётся 2048 токенов, и длинные документы молча обрезаются.
            .numCtx(options.contextTokens)
            .topK(options.topK)
            .repeatPenalty(options.repeatPenalty)
            .repeatLastN(options.repeatLastTokens)
            .seed(options.seed)
            // Модели с «рассуждением» (qwen3 и подобные) не должны тратить токены на скрытые мысли и возвращать их.
            .think(false)
            .returnThinking(false)
            .customHeaders(properties.headers)
            .timeout(properties.httpTimeout)
            // Повторы делает наша цепочка. Повтор внутри клиента скрыл бы сбой и умножил нагрузку (3 уровня по 3 повтора = 27).
            .maxRetries(0)
            // Тексты вопросов и промптов не должны попадать в логи (правило приватности).
            .logRequests(false)
            .logResponses(false)
            .build()
    }

    /**
     * Клиент OpenAI-совместимого API (YandexGPT, DeepSeek, Qwen и другие). Без ключа API клиент не создаётся.
     *
     * @param providerName имя провайдера.
     * @param properties настройки; ключ API обязателен.
     */
    private fun openAiCompatible(
        providerName: String,
        properties: ProviderProperties,
    ): ChatModel {
        val key = properties.apiKey
        require(!key.isNullOrBlank()) {
            "Для провайдера '$providerName' (type: OPENAI_COMPATIBLE) нужен ключ API: bot.llm.providers.$providerName.api-key"
        }
        return OpenAiChatModel
            .builder()
            .baseUrl(properties.baseUrl)
            .apiKey(key)
            .modelName(properties.defaultModel)
            .customHeaders(properties.headers)
            // Дополнительные поля тела запроса (например, отключение режима рассуждений у DeepSeek).
            .customParameters(requestBodyOf(properties.requestBody))
            .timeout(properties.httpTimeout)
            .maxRetries(0)
            .logRequests(false)
            .logResponses(false)
            .build()
    }

    private val json = JsonMapper.builder().build()

    /**
     * Превращает настройку `request-body` в поля тела запроса: значение, которое читается как JSON (`false`, `5`, `{"type":"disabled"}`),
     * становится булевым, числом или объектом, остальное остаётся строкой.
     *
     * @param raw значения из настроек.
     */
    internal fun requestBodyOf(raw: Map<String, String>): Map<String, Any> = raw.mapValues { (_, value) ->
        runCatching { json.readValue(value, Any::class.java) }.getOrNull() ?: value
    }
}
