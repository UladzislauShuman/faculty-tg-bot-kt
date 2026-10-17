package shuman.bsu.famcs.rag.assistant.intent.classifier

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.assistant.intent.classifier.implementation.AlwaysRagClassifier
import shuman.bsu.famcs.rag.assistant.intent.classifier.implementation.LlmIntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.testsupport.FailingLlmClient
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntentClassifierTest {
    private fun classifier(llm: LlmClient) = LlmIntentClassifier(llm, PromptLibrary(), GreetingMatcher())

    @Test
    fun `модель возвращает метку - она и становится веткой`() = runTest {
        val llm = ScriptedLlmClient.replying("SCHEDULE")

        assertEquals(Intent.SCHEDULE, classifier(llm).classify("когда пары у 3 курса?"))
        assertEquals(LlmRole.CLASSIFIER, llm.requests.single().role)
        assertTrue(
            "когда пары у 3 курса?" in llm.requests
                .single()
                .messages
                .last()
                .content,
            "вопрос подставлен в промпт",
        )
    }

    @Test
    fun `метка с лишним текстом и в нижнем регистре тоже разбирается`() = runTest {
        assertEquals(Intent.GENERAL, classifier(ScriptedLlmClient.replying("Класс: general.")).classify("сколько будет 2+2?"))
        assertEquals(Intent.SMALLTALK, classifier(ScriptedLlmClient.replying("smalltalk")).classify("что умеешь?"))
    }

    @Test
    fun `неизвестная метка - RAG`() = runTest {
        assertEquals(Intent.RAG, classifier(ScriptedLlmClient.replying("не знаю")).classify("вопрос"))
        assertEquals(Intent.RAG, classifier(ScriptedLlmClient.replying("")).classify("вопрос"))
    }

    @Test
    fun `чистое приветствие определяется без вызова модели`() = runTest {
        val llm = ScriptedLlmClient.replying("RAG")

        assertEquals(Intent.SMALLTALK, classifier(llm).classify("Привет!"))
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun `при отказе всех провайдеров вопрос идёт в RAG`() = runTest {
        assertEquals(Intent.RAG, classifier(FailingLlmClient()).classify("вопрос"))
    }

    @Test
    fun `выключенный классификатор отправляет всё в RAG`() = runTest {
        assertEquals(Intent.RAG, AlwaysRagClassifier.classify("привет"))
    }

    @Test
    fun `приветствия узнаются, а вопросы с приветствием нет`() {
        val matcher = GreetingMatcher()
        listOf(
            "Привет",
            "привет!!",
            "Здравствуйте.",
            "Добрый день",
            "спасибо большое",
            "Большое спасибо!",
            "пока",
            "До свидания",
            "как дела?",
            "Hello",
        ).forEach { assertTrue(matcher.isGreeting(it), "«$it» — приветствие") }
        listOf("Привет, какой проходной балл?", "покажи расписание", "спасибо, а где деканат", "расскажи про практику", "")
            .forEach { assertFalse(matcher.isGreeting(it), "«$it» — не приветствие") }
    }
}
