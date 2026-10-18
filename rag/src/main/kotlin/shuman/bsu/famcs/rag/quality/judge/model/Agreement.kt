package shuman.bsu.famcs.rag.quality.judge.model

/**
 * Согласие судьи с человеком по одному критерию. Положительный класс — «верно» (PASS).
 *
 * @property truePositive человек и судья сказали «верно».
 * @property trueNegative оба сказали «неверно».
 * @property falsePositive человек «неверно», судья «верно» (судья пропустил ошибку).
 * @property falseNegative человек «верно», судья «неверно» (судья придрался).
 * @property unknown сколько раз судья ответил «не знаю»; в четыре числа выше не входят.
 */
data class Agreement(
    val truePositive: Int,
    val trueNegative: Int,
    val falsePositive: Int,
    val falseNegative: Int,
    val unknown: Int,
) {
    /** Число пар, вошедших в таблицу согласия. */
    val total: Int get() = truePositive + trueNegative + falsePositive + falseNegative

    /** Доля верных ответов, которые судья признал верными: TP / (TP + FN). `NaN`, если верных ответов нет. */
    val tpr: Double get() = truePositive.toDouble() / (truePositive + falseNegative)

    /** Доля неверных ответов, которые судья признал неверными: TN / (TN + FP). `NaN`, если неверных ответов нет. */
    val tnr: Double get() = trueNegative.toDouble() / (trueNegative + falsePositive)

    /**
     * Каппа Коэна: согласие с поправкой на случайное совпадение, κ = (p_o − p_e) / (1 − p_e).
     * Здесь p_o — доля совпавших вердиктов, p_e — вероятность совпасть случайно при тех же долях «верно» у человека и судьи.
     * 1 — полное согласие, 0 — не лучше случайного. `NaN`, если p_e = 1.
     */
    val kappa: Double
        get() {
            val n = total.toDouble()
            val observed = (truePositive + trueNegative) / n
            val humanPass = (truePositive + falseNegative) / n
            val judgePass = (truePositive + falsePositive) / n
            val expected = humanPass * judgePass + (1 - humanPass) * (1 - judgePass)
            return (observed - expected) / (1 - expected)
        }
}
