package shuman.bsu.famcs.rag.quality.judge.model

/**
 * Что показывается судье. Каждый критерий читает только нужные ему поля, остальные игнорируются.
 *
 * @property question вопрос пользователя.
 * @property answer ответ бота, который оценивается.
 * @property reference эталонный ответ; нужен критерию корректности.
 * @property contexts разделы базы знаний, которые видел генератор; нужны критерию верности.
 */
data class JudgeCase(
    val question: String,
    val answer: String,
    val reference: String? = null,
    val contexts: List<String> = emptyList(),
)
