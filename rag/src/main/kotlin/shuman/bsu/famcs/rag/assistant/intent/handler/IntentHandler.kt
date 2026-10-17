package shuman.bsu.famcs.rag.assistant.intent.handler

import shuman.bsu.famcs.rag.assistant.intent.model.HandlerInput
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.assistant.intent.model.Intent

/**
 * Обработчик одной ветки ([Intent]). Новая ветка — новая реализация, без правок оркестратора.
 */
interface IntentHandler {
    val intent: Intent

    /**
     * Готовит ответ ветки.
     *
     * @param input вопрос, запрос после переформулирования и контекст диалога.
     * @return текст ответа, источники и признак «в базе знаний ответа нет».
     */
    suspend fun handle(input: HandlerInput): HandlerOutput
}
