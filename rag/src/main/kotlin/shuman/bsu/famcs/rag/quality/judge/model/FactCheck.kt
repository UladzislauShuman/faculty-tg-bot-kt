package shuman.bsu.famcs.rag.quality.judge.model

/**
 * Проверка кодом: какие точные факты эталона (телефоны, числа, ссылки, почта, фамилии) есть в ответе.
 *
 * Не заменяет судью: совпадение слов не видит отрицания («можно» и «нельзя»). Нужна, чтобы найти расхождения с судьёй и отдать
 * их человеку на просмотр.
 *
 * @property facts факты эталона.
 * @property missing факты, которых нет в ответе.
 */
data class FactCheck(
    val facts: List<String>,
    val missing: List<String>,
) {
    /** Доля найденных фактов; 1, если проверять нечего. */
    val share: Double get() = if (facts.isEmpty()) 1.0 else (facts.size - missing.size).toDouble() / facts.size

    /**
     * Расходится ли проверка с классом судьи: все факты на месте, а судья сказал «неверно», «частично» или «отказ»;
     * или найдено меньше половины фактов, а судья сказал «верно». Такие ответы смотрит человек, вердикт не меняется.
     *
     * @param grade класс судьи.
     */
    fun disagreesWith(grade: CorrectnessGrade): Boolean {
        if (facts.isEmpty() || grade == CorrectnessGrade.UNKNOWN) return false
        return if (grade.verdict == Verdict.PASS) share < HALF else missing.isEmpty()
    }

    private companion object {
        const val HALF = 0.5
    }
}
