package shuman.bsu.famcs.rag.quality.judge.service.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.LlmMessage
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.quality.judge.model.CorrectnessGrade
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCase
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCriterion
import shuman.bsu.famcs.rag.quality.judge.model.JudgeResult
import shuman.bsu.famcs.rag.quality.judge.model.Verdict
import shuman.bsu.famcs.rag.quality.judge.service.AnswerJudge
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Судья на языковой модели с ролью JUDGE.
 *
 * Правила, взятые из практики оценки (Hamel Husain, Хабр): вердикт двоичный, один критерий за вызов, промпт на русском и
 * зафиксирован в файле, судье показывается только то, что нужно критерию (для корректности — эталон и ответ, без вопроса, чтобы
 * слабая модель не оценивала правдоподобие вопроса). Модель судьи задаётся ролью JUDGE и должна отличаться от генератора.
 *
 * @property llm клиент моделей.
 * @property prompts библиотека промптов.
 */
class LlmAnswerJudge(
    private val llm: LlmClient,
    private val prompts: PromptLibrary,
) : AnswerJudge {
    override suspend fun judge(
        criterion: JudgeCriterion,
        case: JudgeCase,
    ): JudgeResult {
        val system = prompts.render(criterion.promptName)
        val user = userMessage(criterion, case)
        val raw = try {
            llm.complete(LlmRequest(LlmRole.JUDGE, listOf(LlmMessage.system(system), LlmMessage.user(user)))).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmUnavailableException) {
            log.warn { "Судья недоступен: ${e.message}" }
            return JudgeResult(criterion, Verdict.UNKNOWN, "судья недоступен", gradeOrNull(criterion, CorrectnessGrade.UNKNOWN))
        }
        return parse(criterion, raw)
    }

    /** Собирает данные для проверки: только поля, которые читает критерий. */
    private fun userMessage(
        criterion: JudgeCriterion,
        case: JudgeCase,
    ): String = when (criterion) {
        JudgeCriterion.FAITHFULNESS -> "КОНТЕКСТ:\n${case.contexts.joinToString("\n\n")}\n\nОТВЕТ:\n${case.answer}"

        JudgeCriterion.CORRECTNESS -> "ЭТАЛОН:\n${requireNotNull(
            case.reference,
        ) { "Для корректности нужен эталон" }}\n\nОТВЕТ:\n${case.answer}"

        JudgeCriterion.RELEVANCE -> "ВОПРОС:\n${case.question}\n\nОТВЕТ:\n${case.answer}"
    }

    /**
     * Разбирает ответ судьи: вердикт — самая левая из меток «НЕ ЗНАЮ», «НЕВЕРНО», «ВЕРНО»; причина — остальной текст.
     * У корректности меток больше: «ВЕРНО+», «ЧАСТИЧНО», «ОТКАЗ»; двоичный вердикт берётся из класса ([CorrectnessGrade.verdict]).
     * Если метки нет, вердикт `UNKNOWN`: молча считать ответ верным или неверным нельзя.
     *
     * @param criterion проверявшийся критерий.
     * @param raw ответ модели.
     */
    internal fun parse(
        criterion: JudgeCriterion,
        raw: String,
    ): JudgeResult {
        val labels = if (criterion == JudgeCriterion.CORRECTNESS) GRADE_LABEL else LABEL
        val match = labels.find(raw.uppercase().replace('Ё', 'Е'))
        if (match == null) {
            log.warn { "Судья не вернул вердикт ($criterion)" }
            return JudgeResult(criterion, Verdict.UNKNOWN, raw.trim().take(MAX_REASON), gradeOrNull(criterion, CorrectnessGrade.UNKNOWN))
        }
        val label = match.value.replace(" ", "").replace("_", "")
        val reason = raw.substring(match.range.last + 1).trim(' ', '\n', ':', '-', '—', '.').take(MAX_REASON)
        if (criterion == JudgeCriterion.CORRECTNESS) {
            val grade = when (label) {
                "НЕЗНАЮ" -> CorrectnessGrade.UNKNOWN
                "НЕВЕРНО" -> CorrectnessGrade.WRONG
                "ЧАСТИЧНО" -> CorrectnessGrade.PARTIAL
                "ОТКАЗ" -> CorrectnessGrade.REFUSAL
                "ВЕРНО+" -> CorrectnessGrade.CORRECT_PLUS
                else -> CorrectnessGrade.CORRECT
            }
            return JudgeResult(criterion, grade.verdict, reason, grade)
        }
        val verdict = when (label) {
            "НЕЗНАЮ" -> Verdict.UNKNOWN
            "НЕВЕРНО" -> Verdict.FAIL
            else -> Verdict.PASS
        }
        return JudgeResult(criterion, verdict, reason)
    }

    /** Класс только для корректности; у остальных критериев `null`. */
    private fun gradeOrNull(
        criterion: JudgeCriterion,
        grade: CorrectnessGrade,
    ): CorrectnessGrade? = grade.takeIf { criterion == JudgeCriterion.CORRECTNESS }

    private companion object {
        /** Метки вердикта; «НЕВЕРНО» стоит раньше «ВЕРНО», чтобы не принять его за часть слова. */
        val LABEL = Regex("НЕ[ _]?ЗНАЮ|НЕВЕРНО|ВЕРНО")

        /** Метки классов корректности; «ВЕРНО+» стоит раньше «ВЕРНО», чтобы плюс не потерялся. */
        val GRADE_LABEL = Regex("НЕ[ _]?ЗНАЮ|НЕВЕРНО|ВЕРНО ?\\+|ВЕРНО|ЧАСТИЧНО|ОТКАЗ")

        /** Длина причины, символы: длиннее не нужно, это пометка для разбора. */
        const val MAX_REASON = 300
    }
}
