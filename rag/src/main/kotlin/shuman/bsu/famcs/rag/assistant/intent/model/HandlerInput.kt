package shuman.bsu.famcs.rag.assistant.intent.model

import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext

/** Что получает обработчик ветки. */
data class HandlerInput(
    /** Вопрос так, как его написал пользователь. */
    val question: String,
    /** Самостоятельная формулировка для поиска (после переформулирования). Совпадает с [question], если переформулировки не было. */
    val query: String,
    val context: DialogContext,
)
