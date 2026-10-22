package shuman.bsu.famcs.rag.llm.configuration

import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.validation.beanvalidation.SpringValidatorAdapter
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

    /**
     * Те же настройки, но с проверкой значений, как при запуске приложения (`@Validated`). Простое связывание ошибок,
     * которые в приложении останавливали запуск (искажение ключа с подчёркиванием, значение типа «любой»), не замечает.
     */
    private fun loadValidated(profile: String): LlmProperties {
        val environment = StandardEnvironment()
        for (name in listOf("application-$profile", "application")) {
            YamlPropertySourceLoader().load(name, ClassPathResource("$name.yaml")).forEach { environment.propertySources.addLast(it) }
        }
        val validator = SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().validator)
        return Binder
            .get(environment)
            .bind("bot.llm", Bindable.of(LlmProperties::class.java), ValidationBindHandler(validator))
            .get()
    }

    @Test
    fun `профили читаются с проверкой значений, как при запуске приложения`() {
        for (profile in listOf("qwen", "deepseek", "ollama-cloud", "yandex")) {
            val properties = loadValidated(profile)
            assertTrue(properties.providers.isNotEmpty(), "профиль $profile")
        }
        assertEquals(mapOf("enable_thinking" to "false"), loadValidated("qwen").providers.getValue("qwen").requestBody)
    }

    @Test
    fun `профиль deepseek отключает режим рассуждений полем тела запроса`() {
        val properties = load("deepseek")

        val provider = properties.providers.getValue("deepseek")
        assertEquals(mapOf("thinking" to """{"type":"disabled"}"""), provider.requestBody)
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

    @Test
    fun `профиль qwen отключает рассуждения, ведёт все роли и судью на qwen, адрес по умолчанию международный`() {
        val properties = load("qwen")

        val provider = properties.providers.getValue("qwen")
        assertEquals(mapOf("enable_thinking" to "false"), provider.requestBody, "ключ с подчёркиванием сохранён скобками")
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", provider.baseUrl)
        for (role in listOf(LlmRole.CLASSIFIER, LlmRole.REWRITER, LlmRole.GENERATOR, LlmRole.JUDGE)) {
            assertEquals(
                "qwen",
                properties.roles
                    .getValue(role)
                    .chain
                    .first()
                    .provider,
                "роль $role",
            )
        }
    }
}
