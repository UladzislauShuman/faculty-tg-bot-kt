package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Настройки промптов (`bot.prompts`). */
@Validated
@ConfigurationProperties("bot.prompts")
data class PromptProperties(
    /**
     * Профиль промптов: папка `prompts/<профиль>/`. В ней лежат только изменённые файлы, остальные берутся из `prompts/base/`.
     * `base` — промпты, перенесённые из Python-версии (точка отсчёта для сравнения).
     */
    @field:Pattern(regexp = "[a-z0-9-]+", message = "Имя профиля промптов: строчные латинские буквы, цифры и дефис") val profile: String,
)
