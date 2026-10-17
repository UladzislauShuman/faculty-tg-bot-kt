package shuman.bsu.famcs.rag.assistant.intent.model

import shuman.bsu.famcs.rag.api.model.AnswerSource

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
