package shuman.bsu.famcs.rag.pipeline

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import shuman.bsu.famcs.rag.llm.LlmRole
import shuman.bsu.famcs.rag.testsupport.CoreHarness
import shuman.bsu.famcs.rag.testsupport.FailingLlmClient
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Приватность журнала: ни при каком исходе в журнал не попадают тексты вопросов и ответов и Telegram ID.
 * Тест собирает все записи (в том числе уровня DEBUG и трассировки исключений) и ищет в них метки.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogPrivacyTest {
    private val questionMarker = "СЕКРЕТНЫЙ_ВОПРОС_МАРКЕР"
    private val answerMarker = "СЕКРЕТНЫЙ_ОТВЕТ_МАРКЕР"
    private val userId = 7_777_777_777L

    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var previousLevel: Level? = null

    @BeforeEach
    fun attach() {
        previousLevel = root.level
        root.level = Level.DEBUG
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        root.detachAppender(appender)
        root.level = previousLevel
    }

    /** Весь вывод журнала одной строкой: сообщение, пары ключ-значение и трассировка. */
    private fun everything() = appender.list.joinToString("\n") { event ->
        buildString {
            append(event.formattedMessage)
            event.keyValuePairs?.forEach { append(" ${it.key}=${it.value}") }
            event.throwableProxy?.let { append("\n").append(ThrowableProxyUtil.asString(it)) }
        }
    }

    private fun assertClean() {
        val text = everything()
        assertTrue(text.isNotBlank(), "журнал не пуст: тест действительно что-то собрал")
        assertTrue(questionMarker !in text, "текст вопроса не должен быть в журнале")
        assertTrue(answerMarker !in text, "текст ответа не должен быть в журнале")
        assertTrue(userId.toString() !in text, "Telegram ID не должен быть в журнале")
    }

    @Test
    fun `успешный ответ не оставляет в журнале текстов и идентификатора`() = runTest {
        val h = CoreHarness(this, ScriptedLlmClient { if (it.role == LlmRole.CLASSIFIER) "RAG" else answerMarker })
        h.consent(userId)

        val reply = h.core.ask(h.request("$questionMarker?", userId = userId))
        advanceUntilIdle()

        assertEquals("ANSWERED", reply.status.name)
        assertClean()
    }

    @Test
    fun `отказ всех провайдеров не оставляет в журнале текстов и идентификатора`() = runTest {
        val h = CoreHarness(this, FailingLlmClient())
        h.consent(userId)

        h.core.ask(h.request("$questionMarker?", userId = userId))

        assertClean()
    }

    @Test
    fun `внутренняя ошибка со сломанной моделью не оставляет в журнале текстов и идентификатора`() = runTest {
        val h = CoreHarness(this, ScriptedLlmClient { if (it.role == LlmRole.CLASSIFIER) "RAG" else error("сбой при ответе") })
        h.consent(userId)

        h.core.ask(h.request("$questionMarker?", userId = userId))

        assertClean()
    }

    @Test
    fun `отказы предпроверок и лимитов не оставляют в журнале текстов и идентификатора`() = runTest {
        val h = CoreHarness(this, ScriptedLlmClient.replying(answerMarker))

        h.core.ask(h.request("$questionMarker?", userId = userId))
        h.consent(userId)
        h.core.ask(h.request(questionMarker.repeat(10), userId = userId))
        h.core.ask(h.request("$questionMarker?", userId = userId, updateId = 5))
        h.core.ask(h.request("$questionMarker?", userId = userId, updateId = 5))

        assertClean()
    }
}
