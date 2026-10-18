package shuman.bsu.famcs.rag.quality.judge.model

/**
 * Итог проверки по одному критерию.
 *
 * @property criterion проверенный критерий.
 * @property verdict вердикт судьи.
 * @property reason причина одной фразой из ответа судьи; нужна при разборе расхождений с человеком.
 */
data class JudgeResult(
    val criterion: JudgeCriterion,
    val verdict: Verdict,
    val reason: String,
)
