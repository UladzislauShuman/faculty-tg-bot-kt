package shuman.bsu.famcs.rag.assistant.intent.classifier.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.assistant.intent.classifier.GreetingMatcher
import shuman.bsu.famcs.rag.assistant.intent.classifier.IntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Выбор ветки моделью с ролью CLASSIFIER. Чистые приветствия отсекаются без вызова модели; при сомнении или сбое выбирается RAG:
 * лучше поискать в базе знаний, чем отказать по ошибке.
 *
 * @property llm клиент моделей.
 * @property prompts библиотека промптов.
 * @property greetings распознавание приветствий без модели.
 */
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
