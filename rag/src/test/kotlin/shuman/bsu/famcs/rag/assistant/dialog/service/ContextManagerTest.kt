package shuman.bsu.famcs.rag.assistant.dialog.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.assistant.configuration.properties.DialogProperties
import shuman.bsu.famcs.rag.assistant.dialog.model.Author
import shuman.bsu.famcs.rag.assistant.dialog.model.DialogTurn
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.platform.text.TokenEstimator
import shuman.bsu.famcs.rag.testsupport.FailingLlmClient
import shuman.bsu.famcs.rag.testsupport.InMemoryConversationRepository
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// advanceUntilIdle помечен экспериментальным, но это штатный способ дождаться фоновых задач в runTest.
@OptIn(ExperimentalCoroutinesApi::class)
class ContextManagerTest {
    private val user = TelegramId(42)
    private val clock = MutableClock()
    private val store = InMemoryConversationRepository()

    private val properties = DialogProperties(
        session = DialogProperties.Session(idleTimeout = Duration.ofMinutes(30)),
        memory = DialogProperties.Memory(historyBudgetTokens = 20, maxPairs = 5, summaryMaxChars = 300, maxMessagesRead = 50),
        rewrite = DialogProperties.Rewrite(DialogProperties.RewriteMode.WHEN_HISTORY, maxChars = 300),
    )

    private fun manager(
        summaryLlm: LlmClient,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ContextManager {
        val estimator = TokenEstimator(charsPerToken = 1.0)
        return ContextManager(
            conversations = store,
            summarizer = ConversationSummarizer(summaryLlm, PromptLibrary(), maxChars = 300),
            window = HistoryWindow(estimator, properties.memory.historyBudgetTokens, properties.memory.maxPairs),
            properties = properties,
            clock = clock,
            scope = scope,
        )
    }

    @Test
    fun `первый вопрос открывает новую сессию с пустой памятью`() = runTest {
        val context = manager(ScriptedLlmClient.replying("сводка"), this).open(user)

        assertEquals(emptyList(), context.history)
        assertNull(context.summary)
        assertEquals(false, context.hasMemory)
    }

    @Test
    fun `после обмена следующий вопрос видит предыдущие реплики по порядку`() = runTest {
        val manager = manager(ScriptedLlmClient.replying("сводка"), this)
        manager.record(manager.open(user), "кто декан?", "Иванов")

        val context = manager.open(user)

        assertEquals(listOf(DialogTurn(Author.USER, "кто декан?"), DialogTurn(Author.ASSISTANT, "Иванов")), context.history)
        assertTrue(context.hasMemory)
    }

    @Test
    fun `пауза дольше таймаута начинает новую сессию с чистым контекстом`() = runTest {
        val manager = manager(ScriptedLlmClient.replying("сводка"), this)
        val first = manager.open(user)
        manager.record(first, "вопрос", "ответ")

        clock.advance(Duration.ofMinutes(31))
        val second = manager.open(user)

        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(emptyList(), second.history)
    }

    @Test
    fun `пауза короче таймаута сохраняет сессию`() = runTest {
        val manager = manager(ScriptedLlmClient.replying("сводка"), this)
        val first = manager.open(user)
        manager.record(first, "вопрос", "ответ")

        clock.advance(Duration.ofMinutes(29))

        assertEquals(first.sessionId, manager.open(user).sessionId)
    }

    @Test
    fun `команда new закрывает сессию и забывает контекст`() = runTest {
        val manager = manager(ScriptedLlmClient.replying("сводка"), this)
        val first = manager.open(user)
        manager.record(first, "вопрос", "ответ")

        manager.reset(user)
        val second = manager.open(user)

        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(emptyList(), second.history)
    }

    @Test
    fun `когда история не помещается в бюджет, старая часть уходит в сводку в фоне`() = runTest {
        val llm = ScriptedLlmClient.replying("Обсуждали практику.")
        val manager = manager(llm, this)
        // Бюджет 20 «токенов» (символов): обмен из двух реплик по ~15 символов в окно не помещается целиком.
        // Каждое сообщение приходит после того, как фоновая сводка предыдущего успела отработать (как в жизни).
        repeat(3) {
            manager.record(manager.open(user), "вопрос номер $it?", "ответ номер $it.")
            advanceUntilIdle()
        }

        val context = manager.open(user)

        assertEquals("Обсуждали практику.", context.summary)
        assertTrue(llm.requests.all { it.role == LlmRole.SUMMARIZER })
        assertTrue(context.history.size < 6, "в окне осталась только свежая часть диалога")
    }

    @Test
    fun `повторная сводка дополняет прежнюю и не передаёт уже учтённые сообщения`() = runTest {
        val llm = ScriptedLlmClient.replying("сводка")
        val manager = manager(llm, this)
        repeat(4) {
            manager.record(manager.open(user), "вопрос номер $it?", "ответ номер $it.")
            advanceUntilIdle()
        }

        val lastRequest = llm.requests
            .last()
            .messages
            .single()
            .content
        assertTrue("Сводка предыдущей части: сводка" in lastRequest, "прежняя сводка передаётся модели")
        assertTrue("вопрос номер 0?" !in lastRequest, "сообщения, вошедшие в прежнюю сводку, повторно не суммируются")
    }

    @Test
    fun `сбой модели при сводке не ломает диалог и сводка не записывается`() = runTest {
        val failing = FailingLlmClient()
        val manager = manager(failing, this)
        repeat(3) {
            manager.record(manager.open(user), "вопрос номер $it?", "ответ номер $it.")
            advanceUntilIdle()
        }

        val context = manager.open(user)

        assertNull(context.summary)
        assertTrue(failing.calls > 0, "сводку пытались построить")
        // Диалог продолжается в той же сессии, сообщения сохранены: сводка догонит при следующем удачном вызове.
        assertEquals(6, store.recentMessages(context.sessionId, limit = 50).size)
    }

    @Test
    fun `короткий диалог не тратит токены на сводку`() = runTest {
        val llm = ScriptedLlmClient.replying("сводка")
        val manager = manager(llm, this)
        manager.record(manager.open(user), "привет", "здравствуйте")
        advanceUntilIdle()

        assertEquals(0, llm.requests.size)
    }
}
