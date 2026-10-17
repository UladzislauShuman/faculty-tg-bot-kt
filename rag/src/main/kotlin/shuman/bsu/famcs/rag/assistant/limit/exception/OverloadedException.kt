package shuman.bsu.famcs.rag.assistant.limit.exception

/** Очередь вопросов не приняла или не дождалась своего места. Ядро превращает это в ответ «перегрузка». */
class OverloadedException(message: String) : RuntimeException(message)
