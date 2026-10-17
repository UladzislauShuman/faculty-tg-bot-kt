package shuman.bsu.famcs.rag.assistant.pipeline.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.api.model.AnswerStatus
import shuman.bsu.famcs.rag.api.model.RejectionReason
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.QueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation.IdentityQueryRewriter
import shuman.bsu.famcs.rag.assistant.intent.classifier.IntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.knowledge.model.SearchHit
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.testsupport.RagHarness
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import shuman.bsu.famcs.rag.testsupport.TestRag
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверка ядра целиком (предпроверки, лимиты, очередь, конвейер, запись) на подставных зависимостях.
 * Каждый тест описывает один путь запроса из диаграммы «Обработка сообщения».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RagServiceImplTest {
    /** Модель по ролям: классификатор выбирает RAG, генератор отвечает про практику, чат здоровается. */
    private fun llm(
        classifier: String = "RAG",
        generator: String = "Практика проходит осенью.",
        chat: String = "Здравствуйте!",
    ) = ScriptedLlmClient {
        when (it.role) {
            LlmRole.CLASSIFIER -> classifier
            LlmRole.GENERATOR -> generator
            LlmRole.CHAT -> chat
            else -> "сводка"
        }
    }

    private suspend fun TestScope.harness(
        llm: LlmClient = llm(),
        hits: List<SearchHit> = listOf(TestRag.hit("practice")),
        limits: LimitProperties = TestRag.limits,
        rewriter: QueryRewriter = IdentityQueryRewriter,
        classifier: IntentClassifier? = null,
    ) = RagHarness(this, llm, hits, limits = limits, rewriter = rewriter, classifier = classifier).also { it.consent() }

    // --- Предпроверки ---

    @Test
    fun `без согласия вопрос отклоняется и модель не вызывается`() = runTest {
        val llm = llm()
        val h = RagHarness(this, llm)

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.REJECTED, reply.status)
        assertEquals(RejectionReason.CONSENT_REQUIRED, reply.rejection)
        assertEquals(TestRag.messages.consentRequired, reply.text)
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun `повторная доставка обновления отклоняется молча`() = runTest {
        val h = harness()
        h.core.ask(h.request("Когда практика?", updateId = 7))

        val repeated = h.core.ask(h.request("Когда практика?", updateId = 7))

        assertEquals(RejectionReason.DUPLICATE, repeated.rejection)
        assertFalse(repeated.deliverable)
    }

    @Test
    fun `сообщение старше трёх часов отклоняется молча`() = runTest {
        val h = harness()

        val reply = h.core.ask(h.request("Когда практика?", sentAt = h.clock.instant().minus(Duration.ofHours(4))))

        assertEquals(RejectionReason.STALE, reply.rejection)
        assertFalse(reply.deliverable)
    }

    @Test
    fun `не текст, слишком длинный текст и режим обслуживания получают стандартные ответы`() = runTest {
        val llm = llm()
        val h = harness(llm)

        assertEquals(RejectionReason.UNSUPPORTED_INPUT, h.core.ask(h.request(null)).rejection)
        val tooLong = h.core.ask(h.request("я".repeat(101)))
        assertEquals(RejectionReason.TOO_LONG, tooLong.rejection)
        assertEquals("Не длиннее 100 символов.", tooLong.text)
        h.maintenance.enabled = true
        assertEquals(RejectionReason.MAINTENANCE, h.core.ask(h.request("Когда практика?")).rejection)
        assertEquals(0, llm.requests.size, "ни один отказ предпроверки не тратит токены")
    }

    // --- Ветки ---

    @Test
    fun `вопрос по базе знаний получает ответ, источник с датой проверки и номер для оценки`() = runTest {
        val h = harness()

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.ANSWERED, reply.status)
        assertTrue(reply.text.startsWith("Практика проходит осенью."))
        assertTrue("Источники:\n• Страница practice — https://fpmi.bsu.by/practice (проверено 07.10.2026)" in reply.text)
        assertNotNull(reply.answerId)
        val logged = h.answers.entries.single()
        assertEquals(Intent.RAG, logged.route)
        assertEquals(listOf("https://fpmi.bsu.by/practice"), logged.sources)
        assertEquals(1, h.usage.userMessages(TelegramId(1), LocalDate.now(h.clock)))
        assertEquals(2, h.conversations.recentMessages(h.contexts.open(TelegramId(1)).sessionId, 10).size)
    }

    @Test
    fun `пустая выдача поиска даёт ответ не нашёл без вызова генератора`() = runTest {
        val llm = llm()
        val h = harness(llm, hits = emptyList())

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.NO_INFO, reply.status)
        assertEquals("${TestRag.messages.noInfo}\n\n${TestRag.messages.noInfoHint}", reply.text)
        assertTrue(llm.requests.none { it.role == LlmRole.GENERATOR })
        assertTrue(
            h.answers.entries
                .single()
                .noInfo,
        )
    }

    @Test
    fun `ответ модели с фразой не нашёл - NO_INFO без источников`() = runTest {
        val h = harness(llm(generator = "К сожалению, в моей базе знаний пока нет точной информации по этому вопросу."))

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.NO_INFO, reply.status)
        assertTrue("Источники" !in reply.text)
        assertTrue(TestRag.messages.noInfoHint in reply.text)
        assertEquals(emptyList(), reply.sources)
    }

    @Test
    fun `в ответе со сроками добавляется оговорка о проверке актуальности`() = runTest {
        val h = harness(llm(generator = "Срок подачи документов — до 15.10."))

        val reply = h.core.ask(h.request("Когда подавать документы?"))

        assertTrue(reply.text.endsWith(TestRag.messages.dateCaveat))
    }

    @Test
    fun `приветствие обрабатывается веткой SMALLTALK без поиска и без источников`() = runTest {
        val llm = llm()
        val h = harness(llm)

        val reply = h.core.ask(h.request("Привет!"))

        assertEquals(AnswerStatus.ANSWERED, reply.status)
        assertEquals("Здравствуйте!", reply.text)
        assertEquals(listOf(LlmRole.CHAT), llm.requests.map { it.role }, "классификатор не нужен, поиск не выполнялся")
        assertEquals(
            Intent.SMALLTALK,
            h.answers.entries
                .single()
                .route,
        )
    }

    @Test
    fun `вопрос о расписании получает ссылку без вызова генератора`() = runTest {
        val llm = llm(classifier = "SCHEDULE")
        val h = harness(llm)

        val reply = h.core.ask(h.request("Когда пары у третьего курса?"))

        assertEquals(TestRag.messages.schedule, reply.text)
        assertEquals(listOf(LlmRole.CLASSIFIER), llm.requests.map { it.role })
    }

    @Test
    fun `общий вопрос отвечает чат-ветка без фактов о факультете и без источников`() = runTest {
        val h = harness(llm(classifier = "GENERAL", chat = "Четыре."))

        val reply = h.core.ask(h.request("Сколько будет 2+2?"))

        assertEquals("Четыре.", reply.text)
        assertEquals(
            Intent.GENERAL,
            h.answers.entries
                .single()
                .route,
        )
    }

    @Test
    fun `классификатор получает переформулированный вопрос, а генератор - исходный`() = runTest {
        val seenByClassifier = mutableListOf<String>()
        val classifier = IntentClassifier { query ->
            seenByClassifier += query
            Intent.RAG
        }
        val rewriter = QueryRewriter { _, _ -> "Какой проходной балл на ФПМИ?" }
        val llm = llm()
        val h = harness(llm, rewriter = rewriter, classifier = classifier)

        h.core.ask(h.request("А какой там балл?"))

        assertEquals(listOf("Какой проходной балл на ФПМИ?"), seenByClassifier)
        val generatorQuestion = llm.requests
            .single { it.role == LlmRole.GENERATOR }
            .messages
            .last()
            .content
        assertTrue("А какой там балл?" in generatorQuestion)
        assertEquals(
            "Какой проходной балл на ФПМИ?",
            h.answers.entries
                .single()
                .query,
        )
    }

    @Test
    fun `второй вопрос видит историю первого в запросе к модели`() = runTest {
        val llm = llm()
        val h = harness(llm)
        h.core.ask(h.request("Когда практика?"))

        h.core.ask(h.request("А где её проходят?"))

        val secondGenerator = llm.requests.last { it.role == LlmRole.GENERATOR }.messages
        assertTrue(secondGenerator.any { it.content == "Когда практика?" }, "прошлый вопрос передан как реплика пользователя")
        assertTrue(secondGenerator.any { it.content.startsWith("Практика проходит осенью.") })
    }

    // --- Лимиты ---

    @Test
    fun `шестой вопрос за минуту получает отказ с временем ожидания, через минуту снова можно`() = runTest {
        val h = harness()
        repeat(5) { assertEquals(AnswerStatus.ANSWERED, h.core.ask(h.request("Когда практика?")).status) }

        val sixth = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.RATE_LIMITED, sixth.status)
        assertEquals("Подождите 60 с.", sixth.text)
        h.clock.advance(Duration.ofSeconds(61))
        assertEquals(AnswerStatus.ANSWERED, h.core.ask(h.request("Когда практика?")).status)
    }

    @Test
    fun `исчерпанный суточный лимит отклоняет вопрос и называет лимит`() = runTest {
        val h = harness()
        h.usage.userMessages[1L to LocalDate.now(h.clock)] = 20

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.RATE_LIMITED, reply.status)
        assertEquals("Лимит 20 в сутки.", reply.text)
    }

    @Test
    fun `в режиме экономии действует уменьшенный суточный лимит`() = runTest {
        val h = harness()
        h.usage.systemTokens[LocalDate.now(h.clock)] = 7_000
        h.usage.userMessages[1L to LocalDate.now(h.clock)] = 10

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals("Лимит 10 в сутки.", reply.text)
    }

    @Test
    fun `исчерпанный суточный бюджет системы даёт перегрузку без обращения к моделям`() = runTest {
        val llm = llm()
        val h = harness(llm)
        h.usage.systemTokens[LocalDate.now(h.clock)] = 10_000

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.OVERLOADED, reply.status)
        assertEquals(TestRag.messages.budgetExhausted, reply.text)
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun `второй вопрос того же человека во время обработки первого получает отказ`() = runTest {
        val release = CompletableDeferred<Unit>()
        val slow = ScriptedLlmClient {
            if (it.role == LlmRole.GENERATOR) release.await()
            if (it.role == LlmRole.CLASSIFIER) "RAG" else "Ответ."
        }
        val h = harness(slow)
        val first = async { h.core.ask(h.request("Первый вопрос")) }
        runCurrent()

        val second = h.core.ask(h.request("Второй вопрос"))

        assertEquals(AnswerStatus.RATE_LIMITED, second.status)
        assertEquals(TestRag.messages.busy, second.text)
        release.complete(Unit)
        assertEquals(AnswerStatus.ANSWERED, first.await().status)
    }

    @Test
    fun `когда очередь заполнена, новый вопрос получает перегрузку сразу`() = runTest {
        val release = CompletableDeferred<Unit>()
        val slow = ScriptedLlmClient {
            if (it.role == LlmRole.GENERATOR) release.await()
            if (it.role == LlmRole.CLASSIFIER) "RAG" else "Ответ."
        }
        val tight = TestRag.limits.copy(queue = TestRag.limits.queue.copy(maxConcurrent = 1, maxWaiting = 0))
        val h = harness(slow, limits = tight)
        val first = async { h.core.ask(h.request("Вопрос", userId = 1)) }
        runCurrent()
        h.consent(userId = 2)

        val second = h.core.ask(h.request("Вопрос", userId = 2))

        assertEquals(AnswerStatus.OVERLOADED, second.status)
        assertEquals(TestRag.messages.overloaded, second.text)
        release.complete(Unit)
        first.await()
    }

    // --- Сбои ---

    @Test
    fun `отказ всех провайдеров даёт перегрузку, фиксируется в журнале и не засчитывается в лимит`() = runTest {
        val h = harness(
            shuman.bsu.famcs.rag.testsupport
                .FailingLlmClient(),
        )

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.OVERLOADED, reply.status)
        assertEquals(TestRag.messages.overloaded, reply.text)
        assertEquals(
            AnswerStatus.OVERLOADED,
            h.answers.entries
                .single()
                .status,
        )
        assertEquals(0, h.usage.userMessages(TelegramId(1), LocalDate.now(h.clock)), "сбой бота не отнимает вопрос у человека")
    }

    @Test
    fun `вопрос, не уложившийся в потолок обработки, получает перегрузку`() = runTest {
        val slow = ScriptedLlmClient {
            if (it.role == LlmRole.GENERATOR) delay(100_000)
            if (it.role == LlmRole.CLASSIFIER) "RAG" else "Ответ."
        }
        val h = harness(slow)

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.OVERLOADED, reply.status)
        assertEquals(
            AnswerStatus.OVERLOADED,
            h.answers.entries
                .single()
                .status,
        )
        assertEquals(
            emptyList(),
            h.conversations.recentMessages(h.contexts.open(TelegramId(1)).sessionId, 10),
            "в диалог ничего не записано",
        )
    }

    @Test
    fun `неожиданная ошибка превращается в статус ERROR, а не в исключение`() = runTest {
        val broken = ScriptedLlmClient { if (it.role == LlmRole.GENERATOR) error("сломалось") else "RAG" }
        val h = harness(broken)

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.ERROR, reply.status)
        assertEquals(TestRag.messages.error, reply.text)
    }

    @Test
    fun `сбой записи в журнал не теряет готовый ответ, только кнопки оценки`() = runTest {
        val h = harness()
        h.answers.failing = true

        val reply = h.core.ask(h.request("Когда практика?"))

        assertEquals(AnswerStatus.ANSWERED, reply.status)
        assertTrue(reply.text.startsWith("Практика проходит осенью."))
        assertNull(reply.answerId)
    }

    @Test
    fun `отмена корутины не превращается в ответ`() = runTest {
        val never = ScriptedLlmClient { delay(Long.MAX_VALUE).let { "" } }
        val h = harness(never)
        val job = launch { h.core.ask(h.request("Когда практика?")) }
        runCurrent()

        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
    }
}
