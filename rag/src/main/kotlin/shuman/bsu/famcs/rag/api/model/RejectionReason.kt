package shuman.bsu.famcs.rag.api.model

/** Причина отказа при статусе [AnswerStatus.REJECTED]. */
enum class RejectionReason(
    /** Молчаливый отказ: пользователю ничего не отправляется (дубль доставки, устаревшее обновление). */
    val silent: Boolean = false,
) {
    CONSENT_REQUIRED,
    UNSUPPORTED_INPUT,
    TOO_LONG,
    MAINTENANCE,
    DUPLICATE(silent = true),
    STALE(silent = true),
}
