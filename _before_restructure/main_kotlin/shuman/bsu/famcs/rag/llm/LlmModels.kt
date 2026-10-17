package shuman.bsu.famcs.rag.llm

import kotlin.time.Duration

/*
 * Модель данных слоя LLM: что просим у модели и что получаем.
 *
 * Шаги пайплайна (переформулирование, классификация, генерация…) знают только [LlmClient], [LlmRole] и эти типы.
 * Какой провайдер и какая модель стоят за ролью, определяет конфигурация.
 */

/** Роль вызова модели. Для каждой роли в конфиге свой провайдер, модель, температура и таймаут. */
enum class LlmRole {
    /** Выбор ветки обработки: RAG, расписание, приветствие, общий вопрос. Короткий ответ, температура 0. */
    CLASSIFIER,

    /** Превращает вопрос с местоимениями в самостоятельный запрос для поиска. */
    REWRITER,

    /** Ответ по найденным документам. Главная статья расхода токенов. */
    GENERATOR,

    /** Ответ без документов: приветствия и общие вопросы. */
    CHAT,

    /** Сжатие старой части диалога в краткую сводку. */
    SUMMARIZER,
}

/** Одно сообщение в запросе к модели. */
data class LlmMessage(
    val author: Author,
    val content: String,
) {
    enum class Author { SYSTEM, USER, ASSISTANT }

    companion object {
        fun system(content: String) = LlmMessage(Author.SYSTEM, content)

        fun user(content: String) = LlmMessage(Author.USER, content)

        fun assistant(content: String) = LlmMessage(Author.ASSISTANT, content)
    }
}

/** Запрос к модели. Параметры генерации (температура, лимит ответа, таймаут) берутся из настроек роли. */
data class LlmRequest(
    val role: LlmRole,
    val messages: List<LlmMessage>,
) {
    init {
        require(messages.isNotEmpty()) { "Запрос к модели должен содержать хотя бы одно сообщение" }
    }
}

/** Расход токенов одним вызовом. Нужен для лимитов, бюджета и метрик. */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    companion object {
        val ZERO = TokenUsage(inputTokens = 0, outputTokens = 0)
    }
}

/** Ответ модели вместе с тем, кто его дал и сколько это стоило. */
data class LlmReply(
    val text: String,
    val usage: TokenUsage,
    val provider: String,
    val model: String,
    val elapsed: Duration,
)

/**
 * Единая точка вызова языковых моделей для всего ядра.
 *
 * Внутри: выбор провайдера по роли, повтор, резерв, ограничитель частоты ошибок, лимит одновременных вызовов.
 * При полном отказе бросает [LlmUnavailableException].
 */
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmReply
}

/** Получатель сведений о расходе токенов (суточные счётчики системы). Вызывается после каждого успешного вызова модели. */
fun interface LlmUsageListener {
    suspend fun onUsage(
        role: LlmRole,
        provider: String,
        model: String,
        usage: TokenUsage,
    )
}
