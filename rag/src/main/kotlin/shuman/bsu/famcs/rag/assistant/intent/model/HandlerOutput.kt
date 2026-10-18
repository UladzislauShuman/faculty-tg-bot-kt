package shuman.bsu.famcs.rag.assistant.intent.model

import shuman.bsu.famcs.rag.api.model.AnswerSource
import shuman.bsu.famcs.rag.knowledge.model.RetrievedCandidate
import shuman.bsu.famcs.rag.knowledge.model.RetrievedPassage

/** Что возвращает обработчик ветки. */
data class HandlerOutput(
    val text: String,
    /** Ответа в базе знаний нет. Такой ответ не показывает источников и считается в «пробелах знаний». */
    val noInfo: Boolean = false,
    val sources: List<AnswerSource> = emptyList(),
    /** Кто сформировал ответ. `null`, если модель не вызывалась. */
    val provider: String? = null,
    val model: String? = null,
    /** Разделы, которые увидела модель. Пусто у веток без поиска и когда поиск ничего не нашёл. Нужны для трассы ответа. */
    val passages: List<RetrievedPassage> = emptyList(),
    /** Кандидаты поиска до выбора реранкера. Нужны для трассы: видно, куда реранкер поставил нужный раздел. */
    val candidates: List<RetrievedCandidate> = emptyList(),
    /** Время поиска, реранкера и генерации ответа, мс. Нули у веток без поиска. */
    val searchMillis: Long = 0,
    val rerankMillis: Long = 0,
    val generationMillis: Long = 0,
)
