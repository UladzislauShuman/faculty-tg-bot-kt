package shuman.bsu.famcs.rag.quality.judge.service

import shuman.bsu.famcs.rag.quality.judge.model.Agreement
import shuman.bsu.famcs.rag.quality.judge.model.Verdict

/**
 * Сверка судьи с человеком: считает таблицу согласия по парам «оценка человека — вердикт судьи».
 * Чистая арифметика без модели, поэтому проверяется обычными тестами.
 */
object AgreementCalculator {
    /**
     * Строит таблицу согласия.
     *
     * @param pairs пары: человек сказал «верно» (`true`) или «неверно» (`false`), и вердикт судьи.
     * @return числа TP, TN, FP, FN и количество ответов «не знаю».
     */
    fun compare(pairs: List<Pair<Boolean, Verdict>>): Agreement {
        var tp = 0
        var tn = 0
        var fp = 0
        var fn = 0
        var unknown = 0
        for ((human, verdict) in pairs) {
            when {
                verdict == Verdict.UNKNOWN -> unknown++
                human && verdict == Verdict.PASS -> tp++
                !human && verdict == Verdict.FAIL -> tn++
                !human -> fp++
                else -> fn++
            }
        }
        return Agreement(tp, tn, fp, fn, unknown)
    }
}
