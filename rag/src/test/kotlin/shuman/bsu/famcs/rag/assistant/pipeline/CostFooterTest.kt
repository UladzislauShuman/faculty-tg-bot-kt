package shuman.bsu.famcs.rag.assistant.pipeline

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.assistant.configuration.properties.ReplyProperties
import shuman.bsu.famcs.rag.assistant.pipeline.service.CostFooter
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Строка стоимости под ответом: расчёт по тарифу, выключенный показ, вопрос без вызовов моделей. */
class CostFooterTest {
    private val enabled = ReplyProperties.Cost(enabled = true, inputPerMillion = 0.15, outputPerMillion = 0.47, currency = "$")

    @Test
    fun `цена считается по входным и выходным токенам отдельно`() {
        val line = CostFooter(enabled).render(TokenUsage(inputTokens = 2000, outputTokens = 100))

        // 2000 * 0,15 / 1e6 + 100 * 0,47 / 1e6 = 0,0003 + 0,000047 = 0,000347
        assertEquals("Цена вопроса ≈ 0,0003 $ (2000 токенов на вход, 100 на выход)", line)
    }

    @Test
    fun `по умолчанию строка выключена`() {
        assertNull(CostFooter(ReplyProperties.Cost()).render(TokenUsage(2000, 100)))
    }

    @Test
    fun `если модели не вызывались, строки нет`() {
        assertNull(CostFooter(enabled).render(TokenUsage.ZERO))
    }

    @Test
    fun `вид строки задаётся шаблоном`() {
        val footer = CostFooter(enabled.copy(template = "{input}/{output} → {cost}{currency}"))

        assertEquals("1000000/0 → 0,1500$", footer.render(TokenUsage(1_000_000, 0)))
    }
}
