package shuman.bsu.famcs.rag.assistant.dialog.rewriter

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.assistant.dialog.model.Author
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogTurn
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation.IdentityQueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation.LlmQueryRewriter
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.identifier.SessionId
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.testsupport.FailingLlmClient
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryRewriterTest {
    private val withHistory = DialogContext(
        sessionId = SessionId(1),
        summary = null,
        history = listOf(DialogTurn(Author.USER, "расскажи про ФПМИ"), DialogTurn(Author.ASSISTANT, "ФПМИ — факультет БГУ.")),
    )
    private val empty = DialogContext(SessionId(1), summary = null, history = emptyList())

    private fun rewriter(
        llm: LlmClient,
        onlyWithHistory: Boolean = true,
        maxChars: Int = 200,
    ) = LlmQueryRewriter(llm, PromptLibrary(), onlyWithHistory, maxChars)

    @Test
    fun `вопрос с историей отправляется в модель вместе с репликами и возвращается переформулированным`() = runTest {
        val llm = ScriptedLlmClient.replying("Какой проходной балл на ФПМИ?")

        val result = rewriter(llm).rewrite("А какой там проходной балл?", withHistory)

        assertEquals("Какой проходной балл на ФПМИ?", result)
        val request = llm.requests.single()
        assertEquals(LlmRole.REWRITER, request.role)
        assertEquals(
            listOf(LlmMessage.Author.SYSTEM, LlmMessage.Author.USER, LlmMessage.Author.ASSISTANT, LlmMessage.Author.USER),
            request.messages.map { it.author },
        )
        assertEquals("А какой там проходной балл?", request.messages.last().content)
    }

    @Test
    fun `первый вопрос без истории не тратит токены на переформулировку`() = runTest {
        val llm = ScriptedLlmClient.replying("не должно вызываться")

        val result = rewriter(llm).rewrite("Где находится деканат?", empty)

        assertEquals("Где находится деканат?", result)
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun `в режиме ALWAYS переформулируется и первый вопрос`() = runTest {
        val llm = ScriptedLlmClient.replying("Где находится деканат ФПМИ?")

        val result = rewriter(llm, onlyWithHistory = false).rewrite("Где деканат?", empty)

        assertEquals("Где находится деканат ФПМИ?", result)
    }

    @Test
    fun `сводка старого диалога передаётся модели в системном сообщении`() = runTest {
        val llm = ScriptedLlmClient.replying("Вопрос")
        val context = empty.copy(summary = "Обсуждали поступление на ПМ.")

        rewriter(llm).rewrite("А сколько там мест?", context)

        assertTrue(
            "Обсуждали поступление на ПМ." in llm.requests
                .single()
                .messages
                .first()
                .content,
        )
    }

    @Test
    fun `кавычки вокруг ответа модели убираются`() = runTest {
        val result = rewriter(ScriptedLlmClient.replying("«Какой проходной балл?»")).rewrite("а балл?", withHistory)

        assertEquals("Какой проходной балл?", result)
    }

    @Test
    fun `слишком длинный ответ - модель начала отвечать - заменяется исходным вопросом`() = runTest {
        val result = rewriter(ScriptedLlmClient.replying("x".repeat(500)), maxChars = 200).rewrite("а балл?", withHistory)

        assertEquals("а балл?", result)
    }

    @Test
    fun `пустой ответ модели заменяется исходным вопросом`() = runTest {
        assertEquals("а балл?", rewriter(ScriptedLlmClient.replying("  ")).rewrite("а балл?", withHistory))
    }

    @Test
    fun `при отказе всех провайдеров ищем по исходному вопросу`() = runTest {
        assertEquals("а балл?", rewriter(FailingLlmClient()).rewrite("а балл?", withHistory))
    }

    @Test
    fun `выключенное переформулирование возвращает вопрос как есть`() = runTest {
        assertEquals("а балл?", IdentityQueryRewriter.rewrite("а балл?", withHistory))
    }
}
