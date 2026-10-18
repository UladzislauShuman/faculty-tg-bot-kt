package shuman.bsu.famcs.rag.eval.model

/**
 * Один вопрос набора проверок качества.
 *
 * @property id номер вопроса в наборе (порядковый).
 * @property question текст вопроса.
 * @property referenceAnswer эталонный ответ, написанный человеком.
 * @property expectedUrl страница, по которой написан вопрос. Пока единственная известная правильная страница.
 * @property scenario название сценария, если вопрос — шаг диалога; `null` у одиночных вопросов.
 * @property step номер шага в сценарии, с нуля.
 */
data class EvalCase(
    val id: Int,
    val question: String,
    val referenceAnswer: String,
    val expectedUrl: String,
    val scenario: String? = null,
    val step: Int = 0,
) {
    /** Вопрос не зависит от истории диалога и годится для проверки поиска без переформулирования. */
    val standalone: Boolean get() = scenario == null
}
