package shuman.bsu.famcs.rag.intent

import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.dialog.toMessages
import shuman.bsu.famcs.rag.llm.LlmClient
import shuman.bsu.famcs.rag.llm.LlmRequest
import shuman.bsu.famcs.rag.llm.LlmRole

/**
 * Ветка без документов: приветствия и общие вопросы. Отвечает модель с ролью CHAT, опираясь только на историю диалога.
 * Отличаются ветки системным промптом: приветствие остаётся дружелюбным, общий вопрос — коротким и без фактов о факультете.
 */
class ChatHandler(
    override val intent: Intent,
    private val systemPromptName: String,
    private val llm: LlmClient,
    private val prompts: PromptLibrary,
) : IntentHandler {
    override suspend fun handle(input: HandlerInput): HandlerOutput {
        val reply = llm.complete(LlmRequest(LlmRole.CHAT, input.context.toMessages(prompts.render(systemPromptName), input.question)))
        return HandlerOutput(text = reply.text.trim(), provider = reply.provider, model = reply.model)
    }
}

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
