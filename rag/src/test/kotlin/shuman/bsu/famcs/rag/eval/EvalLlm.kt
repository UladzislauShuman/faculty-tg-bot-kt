package shuman.bsu.famcs.rag.eval

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import shuman.bsu.famcs.rag.llm.configuration.LlmConfiguration
import shuman.bsu.famcs.rag.llm.configuration.properties.LlmProperties
import shuman.bsu.famcs.rag.llm.service.LlmClient
import shuman.bsu.famcs.rag.llm.service.ReplyValidator
import shuman.bsu.famcs.rag.platform.notification.Alerts
import shuman.bsu.famcs.rag.platform.text.TokenEstimator
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.RecordingNotifier
import java.time.Clock
import java.time.Duration

/**
 * Настоящий клиент языковых моделей для проверок качества: провайдеры, роли и цепочки из `application.yaml`
 * (профили не подключаются, поэтому роли указывают на локальный Ollama). Собирается тем же методом, что в приложении.
 * Нужен запущенный Ollama с моделями, названными в ролях.
 */
object EvalLlm {
    /**
     * Создаёт клиент моделей по настройкам `bot.llm` из `application.yaml`.
     *
     * @return клиент с ролями из конфигурации.
     */
    fun client(): LlmClient {
        val environment = StandardEnvironment()
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).forEach {
            environment.propertySources.addLast(it)
        }
        val properties = Binder.get(environment).bind("bot.llm", LlmProperties::class.java).get()
        return LlmConfiguration().llmClient(
            properties = properties,
            validator = ReplyValidator(properties.replyValidation),
            tokenEstimator = TokenEstimator(charsPerToken = 3.0),
            usageListeners = emptyList(),
            meters = SimpleMeterRegistry(),
            clock = Clock.systemUTC(),
            alerts = Alerts(RecordingNotifier(), MutableClock(), Duration.ofHours(1)),
        )
    }
}
