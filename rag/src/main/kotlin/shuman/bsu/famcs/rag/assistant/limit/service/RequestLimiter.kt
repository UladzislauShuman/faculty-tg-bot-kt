package shuman.bsu.famcs.rag.assistant.limit.service

import shuman.bsu.famcs.rag.assistant.limit.model.Admission
import shuman.bsu.famcs.rag.platform.identifier.TelegramId

/** Лимиты на пользователя: допускает вопрос к обработке или называет причину отказа. */
interface RequestLimiter {
    /**
     * Выполняет [block], только если пользователю можно задать вопрос сейчас; по окончании освобождает занятое место.
     *
     * @param userId Telegram ID пользователя.
     * @param block работа над вопросом.
     * @return [Admission.Completed] с результатом [block] или [Admission.Denied] с причиной отказа.
     */
    suspend fun <T> withAdmission(
        userId: TelegramId,
        block: suspend () -> T,
    ): Admission<T>
}
