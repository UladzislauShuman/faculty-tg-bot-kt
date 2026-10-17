package shuman.bsu.famcs.rag.llm.exception

/** Все провайдеры цепочки отказали. Ядро превращает это в ответ «перегрузка». */
class LlmUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
