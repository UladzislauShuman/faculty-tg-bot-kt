package shuman.bsu.famcs.rag.pipeline

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.core.AnswerSource
import shuman.bsu.famcs.rag.intent.HandlerOutput
import shuman.bsu.famcs.rag.testsupport.TestCore
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplyAssemblerTest {
    private val assembler = ReplyAssembler(TestCore.messages, TestCore.reply)
    private val checked = LocalDate.of(2026, 10, 7)

    @Test
    fun `источники выводятся списком с заголовком, адресом и датой проверки`() {
        val text = assembler.assemble(
            HandlerOutput(
                text = "Практика осенью.",
                sources = listOf(AnswerSource("https://a", "Практика", checked), AnswerSource("https://b", null, null)),
            ),
        )

        assertEquals("Практика осенью.\n\nИсточники:\n• Практика — https://a (проверено 07.10.2026)\n• https://b", text)
    }

    @Test
    fun `из заголовка страницы убирается название сайта после разделителя`() {
        val text = assembler.assemble(
            HandlerOutput(text = "Ответ.", sources = listOf(AnswerSource("https://a", "Студенту | Факультет прикладной математики", null))),
        )

        assertEquals("Ответ.\n\nИсточники:\n• Студенту — https://a", text)
    }

    @Test
    fun `ответ без источников остаётся без заголовка источников`() {
        assertEquals("Привет!", assembler.assemble(HandlerOutput(text = "Привет!")))
    }

    @Test
    fun `ответ не нашёл получает подсказку и не получает источников`() {
        val text = assembler.assemble(
            HandlerOutput(text = "Нет данных.", noInfo = true, sources = listOf(AnswerSource("https://a", "A", checked))),
        )

        assertEquals("Нет данных.\n\n${TestCore.messages.noInfoHint}", text)
    }

    @Test
    fun `подсказка не дублируется, если уже есть в тексте`() {
        val text = assembler.assemble(HandlerOutput(text = "Нет данных. ${TestCore.messages.noInfoHint}", noInfo = true))

        assertEquals(1, Regex(Regex.escape(TestCore.messages.noInfoHint)).findAll(text).count())
    }

    @Test
    fun `оговорка добавляется к ответам со сроками и датами и не добавляется к остальным`() {
        assertTrue(assembler.assemble(HandlerOutput(text = "Срок — до конца месяца.")).endsWith(TestCore.messages.dateCaveat))
        assertTrue(assembler.assemble(HandlerOutput(text = "Приём до 15.10.")).endsWith(TestCore.messages.dateCaveat))
        assertTrue(TestCore.messages.dateCaveat !in assembler.assemble(HandlerOutput(text = "Кафедра на втором этаже.")))
    }
}
