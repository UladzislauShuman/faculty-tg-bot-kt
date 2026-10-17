package shuman.bsu.famcs.rag.knowledge.model

/**
 * Состояние запуска индексации: идёт, завершён, не применён (защита от неполного снимка сайта) или упал.
 */
enum class IndexRunStatus { RUNNING, SUCCEEDED, ABORTED, FAILED }
