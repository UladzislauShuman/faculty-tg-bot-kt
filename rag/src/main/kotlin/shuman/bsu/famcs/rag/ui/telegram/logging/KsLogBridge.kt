package shuman.bsu.famcs.rag.ui.telegram.logging

import dev.inmo.kslog.common.KSLoggerDefaultPlatformLoggerLambda
import dev.inmo.kslog.common.LogLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.ui.telegram.security.TokenRedaction

private val log = KotlinLogging.logger("telegram.library")

/**
 * Перенаправляет журнал библиотеки Telegram в наш журнал.
 *
 * Библиотека пишет через `java.util.logging`, мимо SLF4J: уровни из `application.yaml` на неё не действуют, а вывод не проходит
 * наши правила приватности. Здесь предупреждения и ошибки проходят через [TokenRedaction] без трассировки, остальное
 * (запросы, обновления с текстами вопросов) отбрасывается.
 */
object KsLogBridge {
    /**
     * Подключает журнал библиотеки Telegram к нашему: ошибки и предупреждения идут в SLF4J с вырезанным токеном,
     * остальное отбрасывается. Вызывается до создания бота.
     */
    fun install() {
        KSLoggerDefaultPlatformLoggerLambda = { level, _, message, error ->
            when {
                isIdlePollTimeout(message) -> Unit
                level == LogLevel.ERROR || level == LogLevel.ASSERT -> log.error { describe(message, error) }
                level == LogLevel.WARNING -> log.warn { describe(message, error) }
                else -> Unit
            }
        }
    }

    /**
     * Узнаёт ожидаемый таймаут пустого длинного опроса: клиент ждёт ответа столько же, сколько Telegram держит запрос,
     * поэтому без сообщений каждый опрос заканчивается таймаутом. Это не сбой.
     */
    private fun isIdlePollTimeout(message: Any?): Boolean {
        val text = message?.toString().orEmpty()
        return "HttpRequestTimeoutException" in text && "getUpdates" in text
    }

    /** Собирает строку журнала из сообщения и ошибки библиотеки; токен бота вырезается. */
    private fun describe(
        message: Any?,
        error: Throwable?,
    ): String = TokenRedaction.redact(message?.toString()) + (error?.let { " (${TokenRedaction.describe(it)})" } ?: "")
}
