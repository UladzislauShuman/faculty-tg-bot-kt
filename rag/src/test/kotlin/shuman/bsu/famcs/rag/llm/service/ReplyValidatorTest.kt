package shuman.bsu.famcs.rag.llm.service

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.llm.exception.LlmBadResponseException
import shuman.bsu.famcs.rag.testsupport.TestLlm
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReplyValidatorTest {
    private val validator = ReplyValidator(TestLlm.validation)

    @Test
    fun `нормальный ответ возвращается без пробелов по краям`() {
        assertEquals("Ответ", validator.validate("  Ответ \n"))
    }

    @Test
    fun `пустой и слишком короткий ответ отклоняются`() {
        assertFailsWith<LlmBadResponseException> { validator.validate("") }
        assertFailsWith<LlmBadResponseException> { validator.validate("   ") }
        assertFailsWith<LlmBadResponseException> { validator.validate("а") }
    }

    @Test
    fun `зацикленный ответ отклоняется`() {
        val looping = "Вот ответ. " + "Это повторяется снова и снова. ".repeat(20)
        assertFailsWith<LlmBadResponseException> { validator.validate(looping) }
    }

    @Test
    fun `длинный осмысленный ответ без повторов проходит`() {
        val text = (1..40).joinToString(" ") { "Предложение номер $it описывает отдельный факт." }
        assertEquals(text, validator.validate(text))
    }
}
