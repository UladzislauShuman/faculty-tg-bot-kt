package shuman.bsu.famcs.rag.llm.configuration

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import shuman.bsu.famcs.rag.llm.configuration.properties.LlmProperties
import shuman.bsu.famcs.rag.llm.model.LlmRole
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверка, что профили провайдеров (`application-<профиль>.yaml`) читаются так, как задумано, без запуска приложения. */
class LlmProfilesTest {
    /** Настройки `bot.llm`: сначала файл профиля, затем основной (у профиля приоритет, как у активного профиля Spring). */
    private fun load(profile: String): LlmProperties {
        val environment = StandardEnvironment()
        for (name in listOf("application-$profile", "application")) {
            YamlPropertySourceLoader().load(name, ClassPathResource("$name.yaml")).forEach { environment.propertySources.addLast(it) }
        }
        return Binder.get(environment).bind("bot.llm", LlmProperties::class.java).get()
    }

    @Test
    fun `профиль deepseek отключает режим рассуждений полем тела запроса`() {
        val properties = load("deepseek")

        val provider = properties.providers.getValue("deepseek")
        assertEquals(mapOf("thinking" to mapOf("type" to "disabled")), provider.requestBody)
        assertEquals(
            "deepseek",
            properties.roles
                .getValue(LlmRole.GENERATOR)
                .chain
                .first()
                .provider,
        )
    }

    @Test
    fun `у остальных провайдеров нет лишних полей тела запроса, а роль судьи задана`() {
        val properties = load("ollama-cloud")

        assertTrue(properties.providers.values.all { it.requestBody.isEmpty() })
        assertTrue(LlmRole.JUDGE in properties.roles, "роль судьи есть в настройках")
    }
}
