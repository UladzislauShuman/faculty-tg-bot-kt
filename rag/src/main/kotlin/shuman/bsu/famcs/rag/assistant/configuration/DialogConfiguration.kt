package shuman.bsu.famcs.rag.assistant.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.assistant.configuration.properties.DialogProperties
import shuman.bsu.famcs.rag.assistant.dialog.repository.ConversationRepository
import shuman.bsu.famcs.rag.assistant.dialog.repository.jdbc.ConversationRepositoryJdbcImpl
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.QueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation.IdentityQueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.rewriter.implementation.LlmQueryRewriter
import shuman.bsu.famcs.rag.assistant.dialog.service.ContextManager
import shuman.bsu.famcs.rag.assistant.dialog.service.ConversationSummarizer
import shuman.bsu.famcs.rag.assistant.dialog.service.HistoryWindow
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.runtime.ApplicationScope
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.platform.text.TokenEstimator
import java.time.Clock

/** Сборка модуля диалога: сессии, память (окно и сводка) и переформулирование вопроса. */
@Configuration(proxyBeanMethods = false)
class DialogConfiguration {
    /** Хранилище сессий и сообщений диалога. */
    @Bean
    fun conversationRepository(database: Database): ConversationRepository = ConversationRepositoryJdbcImpl(database)

    /** Окно истории: сколько последних реплик помещается в промпт. */
    @Bean
    fun historyWindow(
        estimator: TokenEstimator,
        properties: DialogProperties,
    ) = HistoryWindow(
        estimator = estimator,
        budgetTokens = properties.memory.historyBudgetTokens,
        maxPairs = properties.memory.maxPairs,
    )

    /** Сводка старой части диалога (модель с ролью SUMMARIZER). */
    @Bean
    fun conversationSummarizer(
        llm: LlmClient,
        prompts: PromptLibrary,
        properties: DialogProperties,
    ) = ConversationSummarizer(llm = llm, prompts = prompts, maxChars = properties.memory.summaryMaxChars)

    /** Менеджер контекста: что бот помнит о диалоге; он же выполняет `/new` для адаптеров. */
    @Bean
    fun contextManager(
        conversations: ConversationRepository,
        summarizer: ConversationSummarizer,
        window: HistoryWindow,
        properties: DialogProperties,
        clock: Clock,
        scope: ApplicationScope,
    ) = ContextManager(
        conversations = conversations,
        summarizer = summarizer,
        window = window,
        properties = properties,
        clock = clock,
        scope = scope,
    )

    /** Переформулирование вопроса в самостоятельный запрос: выключено, при наличии истории или всегда (настройка `rewrite.mode`). */
    @Bean
    fun queryRewriter(
        llm: LlmClient,
        prompts: PromptLibrary,
        properties: DialogProperties,
    ): QueryRewriter = when (properties.rewrite.mode) {
        DialogProperties.RewriteMode.OFF -> {
            IdentityQueryRewriter
        }

        DialogProperties.RewriteMode.WHEN_HISTORY -> {
            LlmQueryRewriter(llm, prompts, onlyWithHistory = true, maxChars = properties.rewrite.maxChars)
        }

        DialogProperties.RewriteMode.ALWAYS -> {
            LlmQueryRewriter(llm, prompts, onlyWithHistory = false, maxChars = properties.rewrite.maxChars)
        }
    }
}
