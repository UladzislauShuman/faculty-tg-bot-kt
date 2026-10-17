package shuman.bsu.famcs.rag.admin.model

/**
 * Показатели качества и стоимости ответов за период.
 *
 * @property answered сколько ответов дано (статусы «отвечено» и «не нашёл»).
 * @property noInfo сколько из них закончились ответом «в базе знаний нет».
 * @property medianLatencyMs медиана времени ответа, мс.
 * @property p95LatencyMs 95-й процентиль времени ответа, мс.
 * @property tokensIn сколько токенов отправлено моделям.
 * @property tokensOut сколько токенов получено от моделей.
 */
data class AnswerQuality(
    val answered: Int,
    val noInfo: Int,
    val medianLatencyMs: Long,
    val p95LatencyMs: Long,
    val tokensIn: Long,
    val tokensOut: Long,
)
