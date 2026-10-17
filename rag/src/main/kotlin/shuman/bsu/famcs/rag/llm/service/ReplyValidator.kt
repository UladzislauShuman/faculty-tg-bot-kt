package shuman.bsu.famcs.rag.llm.service

import shuman.bsu.famcs.rag.llm.configuration.properties.LlmProperties
import shuman.bsu.famcs.rag.llm.exception.LlmBadResponseException

/**
 * Проверяет ответ модели до того, как он уйдёт дальше.
 *
 * Зачем: модели иногда возвращают пустую строку или зацикливаются на одной фразе. Такой ответ нельзя
 * показывать пользователю. Проверка бросает [LlmBadResponseException], цепочка считает это сбоем провайдера
 * и пробует повтор или резерв.
 */
class ReplyValidator(
    private val settings: LlmProperties.ReplyValidationProperties,
) {
    /** Возвращает ответ без пробелов по краям или бросает [LlmBadResponseException]. */
    fun validate(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length < settings.minChars) {
            throw LlmBadResponseException("Ответ модели пуст или слишком короток: ${trimmed.length} симв.")
        }
        if (isLooping(trimmed)) {
            throw LlmBadResponseException("Ответ модели зациклен: хвост повторяется не менее ${settings.loopRepeats} раз")
        }
        return trimmed
    }

    /** Зацикленность: хвост заданной длины встречается в тексте много раз. Короткие тексты не проверяются. */
    private fun isLooping(text: String): Boolean {
        val window = settings.loopWindowChars
        if (text.length < window * settings.loopRepeats) return false
        val tail = text.takeLast(window)
        var count = 0
        var from = 0
        while (true) {
            val found = text.indexOf(tail, from)
            if (found < 0) break
            count++
            from = found + window
        }
        return count >= settings.loopRepeats
    }
}
