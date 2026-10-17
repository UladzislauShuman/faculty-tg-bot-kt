package shuman.bsu.famcs.rag.dialog

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/** Настройки диалога (`bot.dialog`): сессии, память, переформулирование вопроса. */
@Validated
@ConfigurationProperties("bot.dialog")
data class DialogProperties(
    @field:Valid val session: Session,
    @field:Valid val memory: Memory,
    @field:Valid val rewrite: Rewrite,
) {
    data class Session(
        /** Пауза в диалоге, после которой следующий вопрос начинает новую сессию с чистым контекстом. */
        val idleTimeout: Duration,
    )

    data class Memory(
        /** Бюджет последних реплик в промпте, токены. Считается по длине текста (см. `bot.tokens`). */
        @field:Positive val historyBudgetTokens: Int,
        /** Сколько последних пар «вопрос и ответ» остаётся в промпте дословно (если помещаются в бюджет). */
        @field:Positive val maxPairs: Int,
        /** Длина сводки старой части диалога, символы. Передаётся модели как ограничение в промпте. */
        @field:Positive val summaryMaxChars: Int,
        /** Сколько последних сообщений сессии читается из базы. Защищает от очень длинных диалогов. */
        @field:Positive val maxMessagesRead: Int,
    )

    enum class RewriteMode {
        /** Не переформулировать никогда. */
        OFF,

        /** Только если у диалога есть история. Как в Python-версии. */
        WHEN_HISTORY,

        /** Переформулировать каждый вопрос, даже первый. */
        ALWAYS,
    }

    data class Rewrite(
        val mode: RewriteMode,
        /** Переформулировка длиннее этого числа символов считается сбоем модели (она начала отвечать) и отбрасывается. */
        @field:Positive val maxChars: Int,
    )
}

/** Согласие на обработку данных (`bot.consent`). Версия записывается вместе с согласием: смена версии требует нового согласия. */
@Validated
@ConfigurationProperties("bot.consent")
data class ConsentProperties(
    @field:NotBlank val version: String,
    /** Текст, который видит пользователь при `/start`. */
    @field:NotBlank val text: String,
    /** Подпись кнопки согласия. */
    @field:NotBlank val acceptButton: String,
)
