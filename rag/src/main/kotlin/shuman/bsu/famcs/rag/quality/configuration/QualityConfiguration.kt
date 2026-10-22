package shuman.bsu.famcs.rag.quality.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import shuman.bsu.famcs.rag.quality.judge.service.AnswerJudge
import shuman.bsu.famcs.rag.quality.judge.service.implementation.LlmAnswerJudge

/** Сборка средств оценки качества: судья ответов. В ответе пользователю судья не участвует, его зовут проверки и выборочный контроль. */
@Configuration(proxyBeanMethods = false)
class QualityConfiguration {
    /** Судья ответов: модель с ролью JUDGE. */
    @Bean
    fun answerJudge(
        llm: LlmClient,
        prompts: PromptLibrary,
    ): AnswerJudge = LlmAnswerJudge(llm, prompts)
}
