package shuman.bsu.famcs.rag.intent

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.llm.LlmClient
import shuman.bsu.famcs.rag.llm.LlmMessage
import shuman.bsu.famcs.rag.llm.LlmRequest
import shuman.bsu.famcs.rag.llm.LlmRole
import shuman.bsu.famcs.rag.llm.LlmUnavailableException
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/** Настройки классификатора (`bot.intent`). */
@Validated
@ConfigurationProperties("bot.intent")
data class IntentProperties(
    val mode: Mode,
) {
    enum class Mode {
        /** Классификатор выключен: все вопросы идут в RAG, как в базовой версии. */
        OFF,

        /** Ветку выбирает модель с ролью CLASSIFIER; чистые приветствия определяются правилом без вызова модели. */
        LLM,
    }
}

/** Ветка обработки вопроса. */
enum class Intent {
    /** Вопрос о факультете: поиск по базе знаний и ответ по найденным документам. */
    RAG,

    /** Вопрос о расписании: готовых данных нет, пользователю даётся ссылка на сайт. */
    SCHEDULE,

    /** Приветствие, благодарность, вопрос к самому боту. */
    SMALLTALK,

    /** Общий вопрос не по теме факультета. Ответ без документов и без фактов о факультете. */
    GENERAL,
}

/**
 * Выбор ветки обработки по самостоятельной формулировке вопроса.
 *
 * Принцип «сомневаешься — RAG»: ошибка в сторону RAG стоит одного лишнего поиска, а ошибка в другую сторону лишает
 * пользователя ответа по документам.
 */
fun interface IntentClassifier {
    suspend fun classify(query: String): Intent
}

/** Классификатор выключен: все вопросы идут в RAG, как в базовой версии. */
object AlwaysRagClassifier : IntentClassifier {
    override suspend fun classify(query: String) = Intent.RAG
}

class LlmIntentClassifier(
    private val llm: LlmClient,
    private val prompts: PromptLibrary,
    /** Отсекает чистые приветствия без вызова модели: это самый частый и самый дешёвый случай. */
    private val greetings: GreetingMatcher,
) : IntentClassifier {
    override suspend fun classify(query: String): Intent {
        if (greetings.isGreeting(query)) return Intent.SMALLTALK

        val messages = listOf(
            LlmMessage.system(prompts.render("classifier-system")),
            LlmMessage.user(prompts.render("classifier-human", "query" to query)),
        )
        val raw = try {
            llm.complete(LlmRequest(LlmRole.CLASSIFIER, messages)).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmUnavailableException) {
            log.warn { "Классификатор недоступен, вопрос идёт в RAG: ${e.message}" }
            return Intent.RAG
        }
        return parse(raw)
    }

    /** Разбирает ответ модели: берётся первое слово, совпавшее с названием ветки. Любая неопознанная метка — RAG. */
    internal fun parse(raw: String): Intent {
        // Модель иногда отвечает «Класс: RAG» или «SMALLTALK.», поэтому ищем название среди слов, а не требуем точного ответа.
        val intent = NON_LETTERS.split(raw.uppercase()).firstNotNullOfOrNull { word -> Intent.entries.firstOrNull { it.name == word } }
        if (intent == null) log.warn { "Классификатор вернул неизвестную метку, вопрос идёт в RAG" }
        return intent ?: Intent.RAG
    }

    private companion object {
        val NON_LETTERS = Regex("[^A-Z_]+")
    }
}
