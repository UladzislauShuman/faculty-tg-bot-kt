package shuman.bsu.famcs.rag.ui.http.dto

/**
 * Тело ответа на `POST /api/v1/ask`.
 *
 * @property status статус ответа (`ANSWERED`, `NO_INFO`, `REJECTED` и другие).
 * @property text текст для пользователя.
 * @property sources источники ответа.
 * @property answerId номер записи журнала для оценки, если ответ можно оценить.
 * @property elapsedMs время обработки, мс.
 * @property trace трасса ответа; есть только при `trace=true` в запросе.
 */
data class AskResponse(
    val status: String,
    val text: String,
    val sources: List<SourceDto>,
    val answerId: Long?,
    val elapsedMs: Long,
    val trace: TraceDto? = null,
) {
    data class SourceDto(
        val url: String,
        val title: String?,
        val checkedAt: String?,
    )
}
