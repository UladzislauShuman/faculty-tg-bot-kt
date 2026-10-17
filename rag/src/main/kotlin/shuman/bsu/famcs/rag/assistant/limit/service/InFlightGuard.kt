package shuman.bsu.famcs.rag.assistant.limit.service

import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.util.concurrent.ConcurrentHashMap

/** Не более одного вопроса за раз от одного пользователя: иначе один человек занимает несколько мест в очереди. */
class InFlightGuard {
    private val active = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Занимает пользователя: пока предыдущий вопрос не завершён, следующий не принимается.
     *
     * @param userId Telegram ID пользователя.
     * @return `true`, если пользователь был свободен; `false`, если его вопрос уже обрабатывается.
     */
    fun tryEnter(userId: TelegramId): Boolean = active.add(userId.value)

    /**
     * Освобождает пользователя после завершения (или отказа) обработки вопроса.
     *
     * @param userId Telegram ID пользователя.
     */
    fun leave(userId: TelegramId) {
        active.remove(userId.value)
    }
}
