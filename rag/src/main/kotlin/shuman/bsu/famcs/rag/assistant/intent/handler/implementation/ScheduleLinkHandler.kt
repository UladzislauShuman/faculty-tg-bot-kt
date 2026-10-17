package shuman.bsu.famcs.rag.assistant.intent.handler.implementation

import shuman.bsu.famcs.rag.assistant.intent.handler.IntentHandler
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerInput
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.assistant.intent.model.Intent

/**
 * Вопросы о расписании: структурных данных пока нет, поэтому пользователю даётся ссылка на сайт. Модель не вызывается.
 * Когда появится выборка из Postgres, она заменит этот обработчик, не меняя остальных.
 */
class ScheduleLinkHandler(
    private val text: String,
) : IntentHandler {
    override val intent = Intent.SCHEDULE

    override suspend fun handle(input: HandlerInput) = HandlerOutput(text = text)
}
