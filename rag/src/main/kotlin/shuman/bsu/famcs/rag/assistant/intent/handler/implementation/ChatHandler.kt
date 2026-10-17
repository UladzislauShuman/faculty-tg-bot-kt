package shuman.bsu.famcs.rag.assistant.intent.handler.implementation

import shuman.bsu.famcs.rag.assistant.dialog.service.toMessages
import shuman.bsu.famcs.rag.assistant.intent.handler.IntentHandler
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerInput
import shuman.bsu.famcs.rag.assistant.intent.model.HandlerOutput
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.llm.model.LlmRequest
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary

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
