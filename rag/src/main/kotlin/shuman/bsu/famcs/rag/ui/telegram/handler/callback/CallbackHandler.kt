package shuman.bsu.famcs.rag.ui.telegram.handler.callback

import shuman.bsu.famcs.rag.ui.telegram.model.IncomingCallback

/** Обработчик нажатий кнопок одного вида (согласие, удаление данных, оценка). */
interface CallbackHandler {
    /**
     * Проверяет, что обработчик отвечает за кнопку.
     *
     * @param data данные кнопки.
     */
    fun supports(data: String): Boolean

    /**
     * Обрабатывает нажатие.
     *
     * @param callback нажатие кнопки.
     */
    suspend fun handle(callback: IncomingCallback)
}
