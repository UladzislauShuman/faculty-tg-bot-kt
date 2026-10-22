package shuman.bsu.famcs.rag.quality.judge.model

/**
 * Итог проверки по одному критерию.
 *
 * @property criterion проверенный критерий.
 * @property verdict вердикт судьи.
 * @property reason причина одной фразой из ответа судьи; нужна при разборе расхождений с человеком.
 * @property grade подробный класс; есть только у корректности.
 */
data class JudgeResult(
    val criterion: JudgeCriterion,
    val verdict: Verdict,
    val reason: String,
    val grade: CorrectnessGrade? = null,
)
