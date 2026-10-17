package shuman.bsu.famcs.rag.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.admin.AdminCommands
import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.ChatId
import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.TokenEstimator
import shuman.bsu.famcs.rag.common.UpdateId
import shuman.bsu.famcs.rag.core.AnswerStatus
import shuman.bsu.famcs.rag.core.BotCore
import shuman.bsu.famcs.rag.core.CoreReply
import shuman.bsu.famcs.rag.core.RejectionReason
import shuman.bsu.famcs.rag.dialog.ContextManager
import shuman.bsu.famcs.rag.dialog.ConversationSummarizer
import shuman.bsu.famcs.rag.dialog.HistoryWindow
import shuman.bsu.famcs.rag.feedback.FeedbackService
import shuman.bsu.famcs.rag.testsupport.InMemoryConversationStore
import shuman.bsu.famcs.rag.testsupport.InMemoryUserDirectory
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import shuman.bsu.famcs.rag.testsupport.TestCore
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class FakeGateway : TelegramGateway {
    data class Sent(val chatId: ChatId, val text: String, val buttons: List<Button>)

    val sent = CopyOnWriteArrayList<Sent>()
    val answers = CopyOnWriteArrayList<Pair<String, String?>>()
    val buttonsRemoved = CopyOnWriteArrayList<Long>()
    var typing = 0
    var blocked = false
    private var nextMessageId = 100L

    override suspend fun sendText(
        chatId: ChatId,
        text: String,
        buttons: List<Button>,
    ): Long {
        if (blocked) throw UserBlockedBotException("blocked")
        sent += Sent(chatId, text, buttons)
        return nextMessageId++
    }

    override suspend fun showTyping(chatId: ChatId) {
        typing++
    }

    override suspend fun answerCallback(
        callbackId: String,
        text: String?,
    ) {
        answers += callbackId to text
    }

    override suspend fun removeButtons(
        chatId: ChatId,
        messageId: Long,
    ) {
        buttonsRemoved += messageId
    }
}

private class FakeFeedback(var accept: Boolean = true) : FeedbackService {
    val ratings = CopyOnWriteArrayList<Triple<AnswerId, TelegramId, Int>>()

    override suspend fun rate(
        answerId: AnswerId,
        userId: TelegramId,
        rating: Int,
        reason: String?,
    ): Boolean {
        if (accept) ratings += Triple(answerId, userId, rating)
        return accept
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBotHandlerTest {
    private val user = TelegramId(10)
    private val chat = ChatId(10)
    private val admin = TelegramId(99)
    private val clock = MutableClock()

    private val properties = TelegramProperties(
        enabled = true,
        token = "test-token",
        adminIds = setOf(99),
        pollTimeout = Duration.ofSeconds(30),
        typingInterval = Duration.ofSeconds(4),
        typingStartDelay = Duration.ofSeconds(1),
        maxConcurrentUpdates = 10,
        messageMaxChars = 60,
        sendRetries = 2,
        ratingLabel = "{n}★",
        texts = TelegramProperties.Texts(
            welcome = "Привет, задайте вопрос.",
            help = "Справка.",
            newSession = "Начали заново.",
            forgetConfirm = "Удалить данные?",
            forgetButton = "Удалить",
            forgotten = "Данные удалены.",
            consentAccepted = "Согласие принято.",
            ratingThanks = "Спасибо!",
            unknownCommand = "Не знаю такой команды.",
            notAdmin = "Только для администраторов.",
        ),
    )

    private class Fixture(
        val gateway: FakeGateway,
        val users: InMemoryUserDirectory,
        val feedback: FakeFeedback,
        val adminCalls: MutableList<Triple<TelegramId, String, List<String>>>,
        val store: InMemoryConversationStore,
        val handler: TelegramBotHandler,
    )

    private fun fixture(
        scope: TestScope,
        core: BotCore = BotCore { CoreReply(AnswerStatus.ANSWERED, "Ответ.", answerId = AnswerId(7)) },
        feedback: FakeFeedback = FakeFeedback(),
    ): Fixture {
        val gateway = FakeGateway()
        val users = InMemoryUserDirectory()
        val store = InMemoryConversationStore()
        val adminCalls = mutableListOf<Triple<TelegramId, String, List<String>>>()
        val contexts = ContextManager(
            store = store,
            summarizer = ConversationSummarizer(ScriptedLlmClient.replying("сводка"), PromptLibrary(), maxChars = 300),
            window = HistoryWindow(TokenEstimator(3.0), budgetTokens = 1000, maxPairs = 3),
            properties = TestCore.dialog,
            clock = clock,
            scope = scope,
        )
        val adminCommands = AdminCommands { adminId, command, args ->
            adminCalls += Triple(adminId, command, args)
            "результат $command"
        }
        val handler = TelegramBotHandler(core, users, contexts, feedback, adminCommands, gateway, TestCore.consent, properties, clock)
        return Fixture(gateway, users, feedback, adminCalls, store, handler)
    }

    private var updateCounter = 0L

    private fun message(
        text: String?,
        from: TelegramId = user,
    ) = IncomingMessage(UpdateId(++updateCounter), from, ChatId(from.value), text, clock.instant())

    private fun callback(
        data: String,
        from: TelegramId = user,
    ) = IncomingCallback(UpdateId(++updateCounter), "cb-$updateCounter", from, ChatId(from.value), messageId = 55, data = data)

    // --- Согласие и старт ---

    @Test
    fun `старт нового пользователя показывает текст согласия с кнопкой, а не приветствие`() = runTest {
        val f = fixture(this)

        f.handler.onMessage(message("/start"))

        val sent = f.gateway.sent.single()
        assertEquals(TestCore.consent.text, sent.text)
        assertEquals(listOf(Button(TestCore.consent.acceptButton, "consent:accept")), sent.buttons)
    }

    @Test
    fun `нажатие кнопки согласия записывает согласие, убирает кнопку и приветствует`() = runTest {
        val f = fixture(this)
        f.handler.onMessage(message("/start"))

        f.handler.onCallback(callback("consent:accept"))

        assertTrue(f.users.visit(user, clock.instant()).hasConsent(TestCore.consent.version))
        assertEquals(listOf(55L), f.gateway.buttonsRemoved)
        assertEquals(
            "Согласие принято.",
            f.gateway.answers
                .single()
                .second,
        )
        assertEquals(
            properties.texts.welcome,
            f.gateway.sent
                .last()
                .text,
        )
    }

    @Test
    fun `старт после согласия показывает приветствие`() = runTest {
        val f = fixture(this)
        f.users.grantConsent(user, TestCore.consent.version, clock.instant())

        f.handler.onMessage(message("/start"))

        assertEquals(
            properties.texts.welcome,
            f.gateway.sent
                .single()
                .text,
        )
    }

    @Test
    fun `команда с именем бота и регистром распознаётся`() = runTest {
        val f = fixture(this)
        f.users.grantConsent(user, TestCore.consent.version, clock.instant())

        f.handler.onMessage(message("/Help@fpmi_bot"))

        assertEquals(
            "Справка.",
            f.gateway.sent
                .single()
                .text,
        )
    }

    @Test
    fun `вопрос без согласия получает запрос согласия вместо текста ядра`() = runTest {
        val core = BotCore {
            CoreReply(AnswerStatus.REJECTED, "Нужно согласие.", rejection = RejectionReason.CONSENT_REQUIRED)
        }
        val f = fixture(this, core)

        f.handler.onMessage(message("Когда практика?"))

        assertEquals(
            TestCore.consent.text,
            f.gateway.sent
                .single()
                .text,
        )
        assertEquals(
            1,
            f.gateway.sent
                .single()
                .buttons.size,
        )
    }

    // --- Вопросы и ответы ---

    @Test
    fun `ответ ядра отправляется с пятью кнопками оценки`() = runTest {
        val f = fixture(this)

        f.handler.onMessage(message("Когда практика?"))

        val sent = f.gateway.sent.single()
        assertEquals("Ответ.", sent.text)
        assertEquals(listOf("1★", "2★", "3★", "4★", "5★"), sent.buttons.map { it.text })
        assertEquals("rate:7:3", sent.buttons[2].data)
    }

    @Test
    fun `длинный ответ режется на части, кнопки оценки только у последней`() = runTest {
        val longText = (1..12).joinToString(" ") { "слово$it" } + ". " + (13..24).joinToString(" ") { "слово$it" }
        val core = BotCore { CoreReply(AnswerStatus.ANSWERED, longText, answerId = AnswerId(7)) }
        val f = fixture(this, core)

        f.handler.onMessage(message("вопрос"))

        assertTrue(f.gateway.sent.size >= 2)
        assertTrue(f.gateway.sent.all { it.text.length <= 60 })
        assertEquals(
            emptyList(),
            f.gateway.sent
                .dropLast(1)
                .flatMap { it.buttons },
        )
        assertEquals(
            5,
            f.gateway.sent
                .last()
                .buttons.size,
        )
    }

    @Test
    fun `молчаливый отказ ничего не отправляет`() = runTest {
        val f = fixture(this, BotCore { CoreReply.silent(RejectionReason.DUPLICATE) })

        f.handler.onMessage(message("вопрос"))

        assertEquals(0, f.gateway.sent.size)
    }

    @Test
    fun `ответ без номера для оценки отправляется без кнопок`() = runTest {
        val f = fixture(this, BotCore { CoreReply(AnswerStatus.OVERLOADED, "Перегрузка.") })

        f.handler.onMessage(message("вопрос"))

        assertEquals(
            emptyList(),
            f.gateway.sent
                .single()
                .buttons,
        )
    }

    @Test
    fun `пока ядро думает, показывается печатает, после ответа индикатор останавливается`() = runTest {
        val release = CompletableDeferred<Unit>()
        val core = BotCore {
            release.await()
            CoreReply(AnswerStatus.ANSWERED, "Ответ.")
        }
        val f = fixture(this, core)
        val job = async { f.handler.onMessage(message("вопрос")) }
        runCurrent()
        advanceTimeBy(10_000)
        val whileWaiting = f.gateway.typing

        release.complete(Unit)
        job.await()
        advanceTimeBy(20_000)

        assertEquals(3, whileWaiting, "индикатор: через 1 с после вопроса, затем каждые 4 с")
        assertEquals(whileWaiting, f.gateway.typing, "после ответа индикатор остановлен")
    }

    @Test
    fun `мгновенный отказ укладывается в паузу и не вызывает печатает`() = runTest {
        val f = fixture(this, BotCore { CoreReply(AnswerStatus.RATE_LIMITED, "Лимит.") })

        f.handler.onMessage(message("вопрос"))
        advanceTimeBy(5_000)

        assertEquals(0, f.gateway.typing)
    }

    @Test
    fun `заблокировавший бота пользователь помечается неактивным без падения`() = runTest {
        val f = fixture(this)
        f.gateway.blocked = true

        f.handler.onMessage(message("вопрос"))

        assertEquals(listOf(user), f.users.blocked)
    }

    // --- Оценки ---

    @Test
    fun `оценка сохраняется, кнопки убираются, пользователь получает спасибо`() = runTest {
        val f = fixture(this)

        f.handler.onCallback(callback("rate:7:4"))

        assertEquals(listOf(Triple(AnswerId(7), user, 4)), f.feedback.ratings)
        assertEquals(
            "Спасибо!",
            f.gateway.answers
                .single()
                .second,
        )
        assertEquals(listOf(55L), f.gateway.buttonsRemoved)
    }

    @Test
    fun `подделанные данные кнопки оценки игнорируются`() = runTest {
        val f = fixture(this)

        listOf("rate:abc:3", "rate:7:9", "rate:7", "rate:7:3:1", "rate:", "unknown").forEach { f.handler.onCallback(callback(it)) }

        assertEquals(0, f.feedback.ratings.size)
        assertEquals(6, f.gateway.answers.size, "на каждое нажатие отвечено, чтобы не висел индикатор")
        assertTrue(f.gateway.answers.all { it.second == null })
    }

    @Test
    fun `оценка чужого ответа не принимается и спасибо не говорится`() = runTest {
        val f = fixture(this, feedback = FakeFeedback(accept = false))

        f.handler.onCallback(callback("rate:7:5"))

        assertEquals(
            null,
            f.gateway.answers
                .single()
                .second,
        )
        assertEquals(0, f.gateway.buttonsRemoved.size)
    }

    // --- Новый диалог и удаление данных ---

    @Test
    fun `команда new закрывает сессию`() = runTest {
        val f = fixture(this)
        val before = f.store.activeSession(user, clock.instant(), Duration.ofHours(2)).id

        f.handler.onMessage(message("/new"))

        assertEquals(
            "Начали заново.",
            f.gateway.sent
                .single()
                .text,
        )
        assertNotEquals(before, f.store.activeSession(user, clock.instant(), Duration.ofHours(2)).id)
    }

    @Test
    fun `удаление данных требует подтверждения кнопкой`() = runTest {
        val f = fixture(this)

        f.handler.onMessage(message("/forget"))
        assertEquals(emptyList(), f.users.forgotten, "до подтверждения ничего не удалено")
        assertEquals(
            "forget:confirm",
            f.gateway.sent
                .single()
                .buttons
                .single()
                .data,
        )

        f.handler.onCallback(callback("forget:confirm"))
        assertEquals(listOf(user), f.users.forgotten)
        assertEquals(
            "Данные удалены.",
            f.gateway.sent
                .last()
                .text,
        )
    }

    // --- Администраторы и прочее ---

    @Test
    fun `административная команда от обычного пользователя отклоняется`() = runTest {
        val f = fixture(this)

        f.handler.onMessage(message("/admin_stats"))

        assertEquals(
            "Только для администраторов.",
            f.gateway.sent
                .single()
                .text,
        )
        assertEquals(0, f.adminCalls.size)
    }

    @Test
    fun `администратор выполняет команду с аргументами`() = runTest {
        val f = fixture(this)

        f.handler.onMessage(message("/admin_maintenance on", from = admin))

        assertEquals(listOf(Triple(admin, "maintenance", listOf("on"))), f.adminCalls)
        assertEquals(
            "результат maintenance",
            f.gateway.sent
                .single()
                .text,
        )
    }

    @Test
    fun `неизвестная команда получает подсказку`() = runTest {
        val f = fixture(this)

        f.handler.onMessage(message("/dance"))

        assertEquals(
            "Не знаю такой команды.",
            f.gateway.sent
                .single()
                .text,
        )
    }
}
