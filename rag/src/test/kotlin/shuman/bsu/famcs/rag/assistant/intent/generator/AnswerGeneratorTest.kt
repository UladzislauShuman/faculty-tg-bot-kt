package shuman.bsu.famcs.rag.assistant.intent.generator

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.intent.generator.implementation.LlmAnswerGenerator
import shuman.bsu.famcs.rag.knowledge.model.RetrievedPassage
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.platform.identifier.SessionId
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверка генератора отдельно от поиска: ему подаются заданные разделы, как при проверке «генерация с эталонным контекстом». */
class AnswerGeneratorTest {
    private val context = DialogContext(SessionId(1), summary = null, history = emptyList())

    private fun passage(text: String) = RetrievedPassage(
        parentId = "p",
        text = text,
        url = "https://fpmi.bsu.by/p",
        title = "Страница",
        headings = emptyMap(),
        indexedAt = null,
        relevance = 0.9,
    )

    @Test
    fun `заданные разделы склеиваются в промпт через пустую строку, вопрос идёт последним сообщением`() = runTest {
        val llm = ScriptedLlmClient.replying("Деканом является Орлович Ю. Л.")
        val generator = LlmAnswerGenerator(llm, PromptLibrary(), "нет точной информации")

        val answer = generator.generate("Кто декан?", context, listOf(passage("Раздел А"), passage("Раздел Б")))

        val request = llm.requests.single()
        assertEquals(LlmRole.GENERATOR, request.role)
        assertTrue("Раздел А\n\nРаздел Б" in request.messages.first().content)
        assertTrue("Кто декан?" in request.messages.last().content)
        assertEquals("Деканом является Орлович Ю. Л.", answer.text)
        assertFalse(answer.noInfo)
    }

    @Test
    fun `при включённой подписи каждый раздел начинается с названия страницы и года`() = runTest {
        val llm = ScriptedLlmClient.replying("Ответ")
        val generator = LlmAnswerGenerator(llm, PromptLibrary(), "нет точной информации", passageHeader = true)
        val dean = passage("Декан — Орлович").copy(title = "Деканат | Факультет прикладной математики", year = 2022)

        generator.generate("Кто декан?", context, listOf(dean, passage("Без года")))

        val text = llm.requests
            .single()
            .messages
            .first()
            .content
        assertTrue("[Источник: Деканат, 2022]\nДекан — Орлович" in text)
        assertTrue("[Источник: Страница]\nБез года" in text, "без года подпись без года")
    }

    @Test
    fun `фраза не нашёл из промпта распознаётся без учёта регистра и ё`() = runTest {
        val llm = ScriptedLlmClient.replying("К сожалению, в моей базе знаний пока НЕТ точной информации по этому вопросу.")

        val answer = LlmAnswerGenerator(llm, PromptLibrary(), "нет точной информации").generate("?", context, listOf(passage("Раздел")))

        assertTrue(answer.noInfo)
    }

    @Test
    fun `фраза, которой нет в промпте, не даёт создать генератор`() {
        assertFailsWith<IllegalArgumentException> {
            LlmAnswerGenerator(ScriptedLlmClient.replying(""), PromptLibrary(), "такой фразы в промпте нет")
        }
    }
}
