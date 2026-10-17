package shuman.bsu.famcs.rag.telegram

import dev.inmo.kslog.common.KSLoggerDefaultPlatformLoggerLambda
import dev.inmo.kslog.common.LogLevel
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger("telegram.library")

/**
 * Перенаправляет журнал библиотеки Telegram в наш журнал.
 *
 * Библиотека пишет через `java.util.logging`, мимо SLF4J: уровни из `application.yaml` на неё не действуют, а вывод не проходит
 * наши правила приватности. Здесь предупреждения и ошибки проходят через [TokenRedaction] без трассировки, остальное
 * (запросы, обновления с текстами вопросов) отбрасывается.
 */
object KsLogBridge {
    fun install() {
        KSLoggerDefaultPlatformLoggerLambda = { level, _, message, error ->
            when (level) {
                LogLevel.ERROR, LogLevel.ASSERT -> log.error { describe(message, error) }
                LogLevel.WARNING -> log.warn { describe(message, error) }
                else -> Unit
            }
        }
    }

    private fun describe(
        message: Any?,
        error: Throwable?,
    ): String = TokenRedaction.redact(message?.toString()) + (error?.let { " (${TokenRedaction.describe(it)})" } ?: "")
}
