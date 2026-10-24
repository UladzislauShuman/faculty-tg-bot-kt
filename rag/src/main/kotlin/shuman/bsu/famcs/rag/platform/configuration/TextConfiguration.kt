package shuman.bsu.famcs.rag.platform.configuration

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.platform.configuration.properties.PromptProperties
import shuman.bsu.famcs.rag.platform.configuration.properties.TokenProperties
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.platform.text.TokenEstimator

private val log = KotlinLogging.logger {}

/** Сборка работы с текстом: библиотека промптов и оценка числа токенов. */
@Configuration(proxyBeanMethods = false)
class TextConfiguration {
    /** Промпты из ресурсов (`prompts/<профиль>/`, недостающие из `prompts/base/`). */
    @Bean
    fun promptLibrary(properties: PromptProperties): PromptLibrary =
        PromptLibrary(properties.profile).also { log.info { "Профиль промптов: ${it.profile}" } }

    /** Грубая оценка числа токенов по длине текста: нужна, чтобы уложить историю в бюджет, не вызывая токенизатор модели. */
    @Bean
    fun tokenEstimator(properties: TokenProperties) = TokenEstimator(charsPerToken = properties.charsPerToken)
}
