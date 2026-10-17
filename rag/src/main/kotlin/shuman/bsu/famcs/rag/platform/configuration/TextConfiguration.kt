package shuman.bsu.famcs.rag.platform.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.platform.configuration.properties.TokenProperties
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.platform.text.TokenEstimator

/** Сборка работы с текстом: библиотека промптов и оценка числа токенов. */
@Configuration(proxyBeanMethods = false)
class TextConfiguration {
    /** Промпты, загруженные из ресурсов (`prompts/`). */
    @Bean
    fun promptLibrary() = PromptLibrary()

    /** Грубая оценка числа токенов по длине текста: нужна, чтобы уложить историю в бюджет, не вызывая токенизатор модели. */
    @Bean
    fun tokenEstimator(properties: TokenProperties) = TokenEstimator(charsPerToken = properties.charsPerToken)
}
