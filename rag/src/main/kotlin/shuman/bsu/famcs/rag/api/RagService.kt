package shuman.bsu.famcs.rag.api

import shuman.bsu.famcs.rag.api.model.RagReply
import shuman.bsu.famcs.rag.api.model.RagRequest

/**
 * Вход в систему: контракт «адаптер ↔ ядро». Единственное, что нужно адаптеру для обработки вопроса.
 *
 * Адаптер (Telegram, HTTP для проверок) превращает входящее событие в [RagRequest] и показывает пользователю [RagReply].
 * Система ничего не знает о Telegram. Гарантия: [ask] всегда возвращает ответ с одним из статусов
 * [shuman.bsu.famcs.rag.api.model.AnswerStatus], исключение до пользователя не доходит.
 */
fun interface RagService {
    /** Обрабатывает сообщение пользователя. Никогда не бросает исключение (кроме отмены корутины). */
    suspend fun ask(request: RagRequest): RagReply
}
