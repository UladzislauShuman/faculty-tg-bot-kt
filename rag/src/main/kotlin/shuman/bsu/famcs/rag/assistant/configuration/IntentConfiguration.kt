package shuman.bsu.famcs.rag.assistant.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.assistant.configuration.properties.BotMessagesProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.IntentProperties
import shuman.bsu.famcs.rag.assistant.configuration.properties.RagHandlerProperties
import shuman.bsu.famcs.rag.assistant.intent.classifier.GreetingMatcher
import shuman.bsu.famcs.rag.assistant.intent.classifier.IntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.classifier.implementation.AlwaysRagClassifier
import shuman.bsu.famcs.rag.assistant.intent.classifier.implementation.LlmIntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.generator.AnswerGenerator
import shuman.bsu.famcs.rag.assistant.intent.generator.implementation.LlmAnswerGenerator
import shuman.bsu.famcs.rag.assistant.intent.handler.IntentHandler
import shuman.bsu.famcs.rag.assistant.intent.handler.implementation.ChatHandler
import shuman.bsu.famcs.rag.assistant.intent.handler.implementation.RagHandler
import shuman.bsu.famcs.rag.assistant.intent.handler.implementation.ScheduleLinkHandler
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.knowledge.retrieval.KnowledgeRetriever
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import java.time.Clock

/**
 * Сборка веток ответа и классификатора.
 * Новая ветка добавляется новым бином [IntentHandler] и значением в [Intent]; остальной код не меняется.
 */
@Configuration(proxyBeanMethods = false)
class IntentConfiguration {
    /** Генерация ответа по найденным разделам: модель с ролью GENERATOR. */
    @Bean
    fun answerGenerator(
        llm: LlmClient,
        prompts: PromptLibrary,
        properties: RagHandlerProperties,
    ): AnswerGenerator = LlmAnswerGenerator(llm, prompts, properties.noInfoMarker, properties.passageHeader)

    /** Ветка RAG: поиск в базе знаний и ответ по найденным разделам. */
    @Bean
    fun ragHandler(
        retriever: KnowledgeRetriever,
        generator: AnswerGenerator,
        properties: RagHandlerProperties,
        messages: BotMessagesProperties,
        clock: Clock,
    ): IntentHandler = RagHandler(
        retriever = retriever,
        generator = generator,
        properties = properties,
        noInfoText = messages.noInfo,
        zone = clock.zone,
    )

    /** Ветка SMALLTALK: приветствия и вопросы к самому боту, отвечает модель без базы знаний. */
    @Bean
    fun smalltalkHandler(
        llm: LlmClient,
        prompts: PromptLibrary,
    ): IntentHandler = ChatHandler(Intent.SMALLTALK, "smalltalk-system", llm, prompts)

    /** Ветка GENERAL: общие вопросы без фактов о факультете, отвечает модель без базы знаний. */
    @Bean
    fun generalHandler(
        llm: LlmClient,
        prompts: PromptLibrary,
    ): IntentHandler = ChatHandler(Intent.GENERAL, "general-system", llm, prompts)

    /** Ветка SCHEDULE: готового расписания нет, пользователю даётся ссылка на страницу расписания. */
    @Bean
    fun scheduleHandler(messages: BotMessagesProperties): IntentHandler = ScheduleLinkHandler(messages.schedule)

    /** Классификатор веток: модель с ролью CLASSIFIER либо всегда RAG (режим `OFF` в настройках). */
    @Bean
    fun intentClassifier(
        llm: LlmClient,
        prompts: PromptLibrary,
        properties: IntentProperties,
    ): IntentClassifier = when (properties.mode) {
        IntentProperties.Mode.OFF -> AlwaysRagClassifier
        IntentProperties.Mode.LLM -> LlmIntentClassifier(llm, prompts, GreetingMatcher())
    }
}
