package shuman.bsu.famcs.rag.intent

import shuman.bsu.famcs.rag.core.AnswerSource
import shuman.bsu.famcs.rag.dialog.DialogContext

/** Что получает обработчик ветки. */
data class HandlerInput(
    /** Вопрос так, как его написал пользователь. */
    val question: String,
    /** Самостоятельная формулировка для поиска (после переформулирования). Совпадает с [question], если переформулировки не было. */
    val query: String,
    val context: DialogContext,
)

/** Что возвращает обработчик ветки. */
data class HandlerOutput(
    val text: String,
    /** Ответа в базе знаний нет. Такой ответ не показывает источников и считается в «пробелах знаний». */
    val noInfo: Boolean = false,
    val sources: List<AnswerSource> = emptyList(),
    /** Кто сформировал ответ. `null`, если модель не вызывалась. */
    val provider: String? = null,
    val model: String? = null,
)

/**
 * Обработчик одной ветки ([Intent]). Новая ветка — новая реализация, без правок оркестратора.
 */
interface IntentHandler {
    val intent: Intent

    suspend fun handle(input: HandlerInput): HandlerOutput
}
