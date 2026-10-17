package shuman.bsu.famcs.rag.knowledge.model

/**
 * Режим индексации.
 *
 * - `INCREMENTAL` — обычное обновление: меняются только изменившиеся страницы.
 * - `FULL` — полная пересборка в новую коллекцию; нужна после смены модели эмбеддингов или правил нарезки.
 */
enum class IndexMode { INCREMENTAL, FULL }
