package shuman.bsu.famcs.rag.quality.judge

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.quality.judge.model.CorrectnessGrade
import shuman.bsu.famcs.rag.quality.judge.service.ReferenceFacts
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверка фактов эталона кодом: что извлекается, что находится в ответе, когда код расходится с судьёй. */
class ReferenceFactsTest {
    @Test
    fun `из эталона извлекаются телефон, кабинет, почта, ссылка и фамилии, но не первое слово предложения`() {
        val facts = ReferenceFacts.extract(
            "Кафедра находится в кабинете 548, телефон 209-51-29. Пишите на kotovvm@bsu.by, программа: https://fpmi.bsu.by/docs/a.pdf. " +
                "Заведующий Котов Владимир.",
        )

        assertTrue("209-51-29" in facts)
        assertTrue("548" in facts)
        assertTrue("kotovvm@bsu.by" in facts)
        assertTrue("https://fpmi.bsu.by/docs/a.pdf" in facts)
        assertTrue("Котов" in facts && "Владимир" in facts)
        assertFalse("Кафедра" in facts, "первое слово предложения — не имя")
        assertFalse("51" in facts || "29" in facts, "части телефона не считаются отдельными числами")
    }

    @Test
    fun `факты находятся в ответе с другими падежами, форматом телефона и закодированной ссылкой`() {
        val facts = ReferenceFacts.extract("Деканом является Орлович Юрий Леонидович, кабинет 433, телефон (+375-17) 209-52-45.")
        val answer = "Юрий Леонидович Орлович принимает в кабинете 433, тел. +375172095245. Ссылка: [декан](https://fpmi.bsu.by/dean)"

        val check = ReferenceFacts.check(facts, answer)

        assertEquals(emptyList(), check.missing)
        assertEquals(1.0, check.share)
    }

    @Test
    fun `падеж фамилии и закодированная ссылка совпадают с эталоном`() {
        val check = ReferenceFacts.check(
            listOf("Орлович", "https://fpmi.bsu.by/sites/default/files/docs/%D0%A4%D0%9F%D0%9C%D0%98.pdf"),
            "Приём ведёт Орловича Ю. Л.; файл https://fpmi.bsu.by/sites/default/files/docs/ФПМИ.pdf",
        )

        assertEquals(emptyList(), check.missing)
    }

    @Test
    fun `заданные списком ключевые факты заменяют извлечённые`() {
        val facts = ReferenceFacts.extract("Три этапа ICPC.", explicit = listOf("чемпионат РБ", "финал Северной Евразии", "финал ICPC"))

        val full = ReferenceFacts.check(facts, "Этапы: чемпионат Республики Беларусь, финал Северной Евразии и мировой финал ICPC.")
        val partial = ReferenceFacts.check(facts, "Этапы: чемпионат Беларуси и финал ICPC.")

        assertEquals(3, facts.size)
        assertEquals(emptyList(), full.missing, "слова короче четырёх букв («РБ») не проверяются")
        assertEquals(listOf("финал Северной Евразии"), partial.missing)
    }

    @Test
    fun `расхождение с судьёй — все факты есть, а судья сказал неверно, или фактов меньше половины, а судья сказал верно`() {
        val allFound = ReferenceFacts.check(listOf("433", "Орлович"), "Орлович, кабинет 433")
        val noneFound = ReferenceFacts.check(listOf("433", "Орлович"), "Деканом является Мандрик")
        val nothingToCheck = ReferenceFacts.check(emptyList(), "любой ответ")

        assertTrue(allFound.disagreesWith(CorrectnessGrade.WRONG))
        assertTrue(allFound.disagreesWith(CorrectnessGrade.PARTIAL))
        assertFalse(allFound.disagreesWith(CorrectnessGrade.CORRECT_PLUS))
        assertTrue(noneFound.disagreesWith(CorrectnessGrade.CORRECT))
        assertFalse(noneFound.disagreesWith(CorrectnessGrade.WRONG))
        assertFalse(nothingToCheck.disagreesWith(CorrectnessGrade.WRONG), "без фактов расхождения нет")
    }

    @Test
    fun `аббревиатуры прописными не извлекаются, ведущие нули и падежи пятибуквенных слов не мешают`() {
        val facts = ReferenceFacts.extract("Конференция ФПМИ проходит со 02 по 05 декабря согласно Указу Президента.")

        assertFalse("ФПМИ" in facts)
        assertTrue("2" in facts && "5" in facts)
        assertEquals(emptyList(), ReferenceFacts.check(facts, "Пройдёт со 2 по 5 декабря, в соответствии с Указом Президента.").missing)
    }
}
