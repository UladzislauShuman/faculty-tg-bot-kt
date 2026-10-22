package shuman.bsu.famcs.rag.eval.model

/**
 * Итог поиска по одному вопросу.
 *
 * @property case вопрос набора.
 * @property poolHit нужная страница была среди кандидатов гибридного поиска до реранкера.
 * @property rank место нужной страницы в итоговой выдаче (с 1); `null`, если её там нет.
 * @property urls страницы итоговой выдачи по порядку.
 * @property scores оценки реранкера итоговой выдачи по порядку.
 */
data class RetrievalRow(
    val case: EvalCase,
    val poolHit: Boolean,
    val rank: Int?,
    val urls: List<String>,
    val scores: List<Double?>,
)
