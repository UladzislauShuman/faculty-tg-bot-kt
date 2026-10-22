package shuman.bsu.famcs.rag.quality.judge

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.quality.judge.model.CorrectnessGrade
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCase
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCriterion
import shuman.bsu.famcs.rag.quality.judge.model.Verdict
import shuman.bsu.famcs.rag.quality.judge.service.implementation.LlmAnswerJudge
import shuman.bsu.famcs.rag.testsupport.FailingLlmClient
import shuman.bsu.famcs.rag.testsupport.ScriptedLlmClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверка судьи на подставной модели: что видит судья, как разбирается вердикт, что бывает при сбое. */
class AnswerJudgeTest {
    private val case = JudgeCase(
        question = "Кто декан?",
        answer = "Деканом является Орлович Ю. Л.",
        reference = "Деканом факультета является Орлович Юрий Леонидович.",
        contexts = listOf("Декан — Орлович Юрий Леонидович.", "Заместитель декана — Иванов."),
    )

    private fun judge(llm: ScriptedLlmClient) = LlmAnswerJudge(llm, PromptLibrary())

    @Test
    fun `верность видит разделы и ответ, но не эталон`() = runTest {
        val llm = ScriptedLlmClient.replying("ВЕРНО\nвсе утверждения есть в контексте")

        val result = judge(llm).judge(JudgeCriterion.FAITHFULNESS, case)

        val request = llm.requests.single()
        assertEquals(LlmRole.JUDGE, request.role)
        val text = request.messages.last().content
        assertTrue("Декан — Орлович Юрий Леонидович.\n\nЗаместитель декана — Иванов." in text)
        assertTrue("Деканом является Орлович Ю. Л." in text)
        assertFalse("Деканом факультета является" in text, "эталон верности не нужен")
        assertEquals(Verdict.PASS, result.verdict)
        assertEquals("все утверждения есть в контексте", result.reason)
    }

    @Test
    fun `корректность видит эталон и ответ, но не вопрос`() = runTest {
        val llm = ScriptedLlmClient.replying("НЕВЕРНО\nв ответе нет имени")

        val result = judge(llm).judge(JudgeCriterion.CORRECTNESS, case)

        val text = llm.requests
            .single()
            .messages
            .last()
            .content
        assertTrue("ЭТАЛОН:\nДеканом факультета является Орлович Юрий Леонидович." in text)
        assertFalse("Кто декан?" in text, "вопрос сбивает слабого судью: он оценивает правдоподобие вопроса")
        assertEquals(Verdict.FAIL, result.verdict)
    }

    @Test
    fun `релевантность видит вопрос и ответ`() = runTest {
        val llm = ScriptedLlmClient.replying("ВЕРНО")

        judge(llm).judge(JudgeCriterion.RELEVANCE, case)

        val text = llm.requests
            .single()
            .messages
            .last()
            .content
        assertTrue("ВОПРОС:\nКто декан?" in text && "ОТВЕТ:\nДеканом является" in text)
    }

    @Test
    fun `НЕВЕРНО не принимается за ВЕРНО, а НЕ ЗНАЮ за вердикт`() {
        val judge = judge(ScriptedLlmClient.replying(""))

        assertEquals(Verdict.FAIL, judge.parse(JudgeCriterion.RELEVANCE, "Неверно: ответ о другом").verdict)
        assertEquals(Verdict.UNKNOWN, judge.parse(JudgeCriterion.RELEVANCE, "не знаю\nмало данных").verdict)
        assertEquals(Verdict.UNKNOWN, judge.parse(JudgeCriterion.RELEVANCE, "НЕ_ЗНАЮ").verdict)
        assertEquals(Verdict.PASS, judge.parse(JudgeCriterion.RELEVANCE, "  верно.").verdict)
    }

    @Test
    fun `ответ без вердикта и сбой модели дают UNKNOWN, а не угаданный вердикт`() = runTest {
        val noLabel = judge(ScriptedLlmClient.replying("Ответ выглядит хорошо.")).judge(JudgeCriterion.RELEVANCE, case)
        assertEquals(Verdict.UNKNOWN, noLabel.verdict)

        val failed = LlmAnswerJudge(FailingLlmClient(), PromptLibrary()).judge(JudgeCriterion.RELEVANCE, case)
        assertEquals(Verdict.UNKNOWN, failed.verdict)
    }

    @Test
    fun `корректность без эталона не запускается`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            judge(ScriptedLlmClient.replying("ВЕРНО")).judge(JudgeCriterion.CORRECTNESS, case.copy(reference = null))
        }
    }

    @Test
    fun `корректность разбирается по классам, двоичный вердикт следует из класса`() = runTest {
        val expected = mapOf(
            "ВЕРНО+\nесть эталон и кабинет" to (CorrectnessGrade.CORRECT_PLUS to Verdict.PASS),
            "ВЕРНО +\nподробнее эталона" to (CorrectnessGrade.CORRECT_PLUS to Verdict.PASS),
            "ВЕРНО\nсовпадает" to (CorrectnessGrade.CORRECT to Verdict.PASS),
            "ЧАСТИЧНО\nнет телефона" to (CorrectnessGrade.PARTIAL to Verdict.FAIL),
            "НЕВЕРНО\nдругое имя" to (CorrectnessGrade.WRONG to Verdict.FAIL),
            "ОТКАЗ\nбот не нашёл" to (CorrectnessGrade.REFUSAL to Verdict.FAIL),
            "НЕ ЗНАЮ" to (CorrectnessGrade.UNKNOWN to Verdict.UNKNOWN),
        )
        for ((reply, pair) in expected) {
            val result = judge(ScriptedLlmClient.replying(reply)).judge(JudgeCriterion.CORRECTNESS, case)
            assertEquals(pair.first, result.grade, reply)
            assertEquals(pair.second, result.verdict, reply)
        }
    }

    @Test
    fun `у верности и релевантности класса нет, метки ВЕРНО+ и ОТКАЗ им не нужны`() = runTest {
        val result = judge(ScriptedLlmClient.replying("ВЕРНО\nвсё есть в контексте")).judge(JudgeCriterion.FAITHFULNESS, case)

        assertEquals(null, result.grade)
        assertEquals(Verdict.PASS, result.verdict)
    }
}
