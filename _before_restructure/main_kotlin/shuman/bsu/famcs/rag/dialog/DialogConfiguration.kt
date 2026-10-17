package shuman.bsu.famcs.rag.dialog

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.app.ApplicationScope
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.PrivacyProperties
import shuman.bsu.famcs.rag.common.PromptLibrary
import shuman.bsu.famcs.rag.common.TokenEstimator
import shuman.bsu.famcs.rag.common.UserHasher
import shuman.bsu.famcs.rag.llm.LlmClient
import java.time.Clock

/**
 * Сборка модуля диалога: пользователи, сессии, память и переформулирование вопроса.
 */
@Configuration(proxyBeanMethods = false)
class DialogConfiguration {
    @Bean
    fun promptLibrary() = PromptLibrary()

    @Bean
    fun userHasher(properties: PrivacyProperties) = UserHasher(secret = properties.hashSecret, length = properties.hashLength)

    @Bean
    fun userDirectory(
        database: Database,
        hasher: UserHasher,
    ): UserDirectory = JdbcUserDirectory(database, hasher)

    @Bean
    fun conversationStore(database: Database): ConversationStore = JdbcConversationStore(database)

    @Bean
    fun historyWindow(
        estimator: TokenEstimator,
        properties: DialogProperties,
    ) = HistoryWindow(
        estimator = estimator,
        budgetTokens = properties.memory.historyBudgetTokens,
        maxPairs = properties.memory.maxPairs,
    )

    @Bean
    fun conversationSummarizer(
        llm: LlmClient,
        prompts: PromptLibrary,
        properties: DialogProperties,
    ) = ConversationSummarizer(llm = llm, prompts = prompts, maxChars = properties.memory.summaryMaxChars)

    @Bean
    fun contextManager(
        store: ConversationStore,
        summarizer: ConversationSummarizer,
        window: HistoryWindow,
        properties: DialogProperties,
        clock: Clock,
        scope: ApplicationScope,
    ) = ContextManager(
        store = store,
        summarizer = summarizer,
        window = window,
        properties = properties,
        clock = clock,
        scope = scope,
    )

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
