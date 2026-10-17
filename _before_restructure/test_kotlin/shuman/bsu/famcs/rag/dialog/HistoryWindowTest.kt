package shuman.bsu.famcs.rag.dialog

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.common.TokenEstimator
import java.time.Instant
import kotlin.test.assertEquals

class HistoryWindowTest {
    /** Один символ — один токен: бюджет в тестах считается напрямую по длине текста. */
    private val oneCharPerToken = TokenEstimator(charsPerToken = 1.0)

    private fun window(
        budget: Int,
        maxPairs: Int = 10,
    ) = HistoryWindow(oneCharPerToken, budget, maxPairs)

    private fun exchange(
        firstId: Long,
        question: String,
        answer: String,
    ) = listOf(
        StoredMessage(firstId, Author.USER, question, Instant.EPOCH),
        StoredMessage(firstId + 1, Author.ASSISTANT, answer, Instant.EPOCH),
    )

    @Test
    fun `всё помещается в бюджет - в окне все сообщения и переполнения нет`() {
        val messages = exchange(1, "вопрос", "ответ") + exchange(3, "ещё", "ещё ответ")

        val selection = window(budget = 100).select(messages)

        assertEquals(4, selection.kept.size)
        assertEquals(emptyList(), selection.overflow)
    }

    @Test
    fun `старые сообщения не влезают в бюджет и уходят в переполнение`() {
        val messages = exchange(1, "aaaa", "bbbb") + exchange(3, "cc", "dd")

        val selection = window(budget = 5).select(messages)

        assertEquals(listOf(3L, 4L), selection.kept.map { it.id })
        assertEquals(listOf(1L, 2L), selection.overflow.map { it.id })
    }

    @Test
    fun `окно начинается с вопроса пользователя, а не с одинокого ответа`() {
        val messages = exchange(1, "aaaa", "bbbb") + exchange(3, "cc", "dd")

        // Бюджет 8 вмещает «bbbb», «cc», «dd», но «bbbb» — ответ без своего вопроса, поэтому он тоже уходит в переполнение.
        val selection = window(budget = 8).select(messages)

        assertEquals(listOf(3L, 4L), selection.kept.map { it.id })
        assertEquals(listOf(1L, 2L), selection.overflow.map { it.id })
    }

    @Test
    fun `число пар ограничено даже если бюджет большой`() {
        val messages = (0L until 5L).flatMap { exchange(it * 2 + 1, "q$it", "a$it") }

        val selection = window(budget = 1000, maxPairs = 2).select(messages)

        assertEquals(4, selection.kept.size)
        assertEquals(6, selection.overflow.size)
    }

    @Test
    fun `пустая история даёт пустое окно`() {
        val selection = window(budget = 10).select(emptyList())

        assertEquals(emptyList(), selection.kept)
        assertEquals(emptyList(), selection.overflow)
    }

    @Test
    fun `одно слишком длинное сообщение не оставляет окно с ответом без вопроса`() {
        val messages =
            listOf(StoredMessage(1, Author.USER, "q", Instant.EPOCH), StoredMessage(2, Author.ASSISTANT, "a".repeat(50), Instant.EPOCH))

        val selection = window(budget = 10).select(messages)

        assertEquals(emptyList(), selection.kept)
        assertEquals(2, selection.overflow.size)
    }
}
