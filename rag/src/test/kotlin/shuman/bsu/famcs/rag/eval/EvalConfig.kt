package shuman.bsu.famcs.rag.eval

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties

/** Настройки приложения для проверок качества: читаются из `application.yaml` из ресурсов, без Spring-контекста и профилей. */
object EvalConfig {
    /** Раздел `bot.knowledge` с подстановкой значений по умолчанию. */
    fun knowledge(): KnowledgeProperties {
        val environment = StandardEnvironment()
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).forEach {
            environment.propertySources.addLast(it)
        }
        return Binder.get(environment).bind("bot.knowledge", KnowledgeProperties::class.java).get()
    }
}
