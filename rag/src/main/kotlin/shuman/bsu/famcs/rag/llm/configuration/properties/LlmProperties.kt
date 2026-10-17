package shuman.bsu.famcs.rag.llm.configuration.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import shuman.bsu.famcs.rag.llm.model.LlmRole
import java.time.Duration

/**
 * Настройки слоя LLM (`bot.llm` в application.yaml).
 *
 * Значения по умолчанию лежат в YAML с подписями, а не в коде: автор правит числа в одном месте.
 */
@Validated
@ConfigurationProperties("bot.llm")
data class LlmProperties(
    /** Доступные бэкенды моделей по имени. Имя используется в цепочках ролей. */
    @field:NotEmpty val providers: Map<String, @Valid ProviderProperties>,
    /** Настройки каждой роли вызова: параметры генерации и цепочка провайдеров. */
    @field:NotEmpty val roles: Map<LlmRole, @Valid RoleProperties>,
    @field:Valid val resilience: ResilienceProperties,
    @field:Valid val replyValidation: ReplyValidationProperties,
) {
    enum class ProviderType {
        /** Локальный Ollama (`/api/chat`). */
        OLLAMA,

        /** Любой сервис с совместимым с OpenAI API: YandexGPT (AI Studio), DeepSeek, Qwen и другие. */
        OPENAI_COMPATIBLE,
    }

    data class ProviderProperties(
        val type: ProviderType,
        @field:NotBlank val baseUrl: String,
        /** Ключ API. Только из окружения. Для Ollama не нужен. */
        val apiKey: String?,
        /** Модель, с которой создаётся клиент. Реальная модель выбирается для каждого вызова по цепочке роли. */
        @field:NotBlank val defaultModel: String,
        /** Верхняя граница ожидания HTTP-клиента. Не меньше самого длинного таймаута вызова среди ролей. */
        val httpTimeout: Duration,
        /** Сколько вызовов одновременно допускается к провайдеру. Равно квоте провайдера. */
        @field:Positive val maxConcurrentCalls: Int,
        /** Дополнительные заголовки (например, идентификатор каталога Yandex Cloud). Не заданы — заголовков нет. */
        val headers: Map<String, String> = emptyMap(),
        /** Параметры, которые есть только у Ollama. Обязательны при `type: OLLAMA`. */
        @field:Valid val ollama: OllamaProperties?,
    ) {
        /** Ключ API в журнал и сообщения об ошибках не попадает. */
        override fun toString() = "ProviderProperties(type=$type, baseUrl=$baseUrl, defaultModel=$defaultModel, apiKey=***)"
    }

    data class OllamaProperties(
        /** Размер контекстного окна модели в токенах. Больше — больше памяти. */
        @field:Min(512) val contextTokens: Int,
        /** Из скольких самых вероятных токенов выбирать следующий. */
        @field:Positive val topK: Int,
        /** Штраф за повторы. 1.0 — без штрафа. */
        @field:DecimalMin("0.5") @field:DecimalMax("2.0") val repeatPenalty: Double,
        /** На сколько токенов назад смотреть при штрафе за повторы. */
        @field:Positive val repeatLastTokens: Int,
        /** Зерно генератора случайных чисел. `null` — случайное. */
        val seed: Int?,
    )

    data class RoleProperties(
        /** Случайность ответа. 0 — детерминированно (классификация, сводка), около 0.3 — ответы по документам. */
        @field:DecimalMin("0.0") @field:DecimalMax("2.0") val temperature: Double,
        /** Ядерная выборка: учитываются токены, суммарная вероятность которых не больше этого значения. */
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val topP: Double,
        /** Потолок длины ответа в токенах. */
        @field:Positive val maxOutputTokens: Int,
        /** Сколько ждать один вызов, включая ожидание очереди к провайдеру. */
        val callTimeout: Duration,
        /** Провайдеры по порядку: основной, затем резервные. */
        @field:NotEmpty val chain: List<@Valid ChainLink>,
    )

    data class ChainLink(
        @field:NotBlank val provider: String,
        /** Модель этого провайдера для данной роли. */
        @field:NotBlank val model: String,
    )

    data class ResilienceProperties(
        @field:Valid val breaker: BreakerProperties,
        @field:Valid val retry: RetryProperties,
        /** На сколько откладывать провайдера, у которого кончилась квота или деньги. */
        val quotaPause: Duration,
    )

    /** Ограничитель частоты ошибок: при высокой доле сбоев провайдера звать его перестают на время паузы. */
    data class BreakerProperties(
        /** Ширина скользящего окна подсчёта, секунды. */
        @field:Positive val windowSeconds: Int,
        /** Сколько вызовов минимум нужно в окне, чтобы доля ошибок считалась. */
        @field:Positive val minimumCalls: Int,
        /** Доля сбоев в окне, при которой ограничитель размыкается, проценты. */
        @field:Min(1) @field:Max(100) val failureRatePercent: Int,
        /** Сколько провайдер остаётся недоступным после размыкания. */
        val openStateWait: Duration,
        /** Сколько пробных вызовов пропускается после паузы, чтобы проверить, ожил ли провайдер. */
        @field:Positive val halfOpenProbeCalls: Int,
    )

    data class RetryProperties(
        /** Повторов у основного провайдера после временного сбоя (5xx, таймаут). */
        @field:Min(0) val retriesOnPrimary: Int,
        /** Повторов у резервных провайдеров. */
        @field:Min(0) val retriesOnFallback: Int,
        /** Пауза перед повтором. */
        val backoff: Duration,
        /** Случайный разброс паузы, доля от неё (0.5 — плюс-минус половина). Разводит одновременные повторы. */
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val jitter: Double,
    )

    /** Проверка ответа модели до показа пользователю. */
    data class ReplyValidationProperties(
        /** Ответ короче этого числа символов считается пустым. */
        @field:Positive val minChars: Int,
        /** Окно поиска зацикливания, символы. Хвост такой длины сравнивается с остальным текстом. */
        @field:Positive val loopWindowChars: Int,
        /** Сколько раз хвост должен повториться, чтобы ответ посчитали зацикленным. */
        @field:Min(2) val loopRepeats: Int,
    )
}
