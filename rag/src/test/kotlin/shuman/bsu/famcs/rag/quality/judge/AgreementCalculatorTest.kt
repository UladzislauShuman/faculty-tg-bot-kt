package shuman.bsu.famcs.rag.quality.judge

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.quality.judge.model.Verdict
import shuman.bsu.famcs.rag.quality.judge.service.AgreementCalculator
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверка арифметики согласия судьи с человеком на примерах, которые считаются вручную. */
class AgreementCalculatorTest {
    private fun near(
        expected: Double,
        actual: Double,
    ) = assertTrue(abs(expected - actual) < 1e-9, "ожидалось $expected, получено $actual")

    @Test
    fun `таблица согласия, TPR, TNR и каппа на ручном примере`() {
        // Человек: 6 верных, 4 неверных. Судья: из верных 5 признал верными и 1 неверным; из неверных 3 признал неверными, 1 верным.
        val pairs = List(5) { true to Verdict.PASS } + (true to Verdict.FAIL) +
            List(3) { false to Verdict.FAIL } + (false to Verdict.PASS)

        val agreement = AgreementCalculator.compare(pairs)

        assertEquals(5, agreement.truePositive)
        assertEquals(3, agreement.trueNegative)
        assertEquals(1, agreement.falsePositive)
        assertEquals(1, agreement.falseNegative)
        near(5.0 / 6, agreement.tpr)
        near(3.0 / 4, agreement.tnr)
        // p_o = 8/10; человек «верно» 0,6, судья «верно» 0,6; p_e = 0,36 + 0,16 = 0,52; κ = (0,8 − 0,52) / 0,48.
        near((0.8 - 0.52) / 0.48, agreement.kappa)
    }

    @Test
    fun `ответы не знаю считаются отдельно и в таблицу не входят`() {
        val agreement = AgreementCalculator.compare(listOf(true to Verdict.PASS, true to Verdict.UNKNOWN, false to Verdict.UNKNOWN))

        assertEquals(1, agreement.total)
        assertEquals(2, agreement.unknown)
    }

    @Test
    fun `полное согласие даёт каппу один`() {
        val agreement = AgreementCalculator.compare(
            listOf(
                true to Verdict.PASS,
                true to Verdict.PASS,
                false to Verdict.FAIL,
                false to Verdict.FAIL,
            ),
        )

        near(1.0, agreement.kappa)
    }
}
