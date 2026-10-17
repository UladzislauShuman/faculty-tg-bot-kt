package shuman.bsu.famcs.rag.dialog

import shuman.bsu.famcs.rag.common.TokenEstimator

/**
 * Отбор реплик для промпта: самые свежие, что помещаются в бюджет токенов.
 *
 * Зачем бюджет в токенах, а не «последние N сообщений»: ответы бота бывают в десять раз длиннее вопросов, и N сообщений
 * то занимают сотню токенов, то несколько тысяч. Бюджет делает размер промпта предсказуемым, а значит, и стоимость.
 */
class HistoryWindow(
    private val estimator: TokenEstimator,
    private val budgetTokens: Int,
    private val maxPairs: Int,
) {
    /**
     * Результат отбора.
     * [kept] — реплики для промпта, [overflow] — более старые, которые в окно не вошли (кандидаты на сводку).
     */
    data class Selection(
        val kept: List<StoredMessage>,
        val overflow: List<StoredMessage>,
    )

    /** [messages] — сообщения сессии от старых к новым. */
    fun select(messages: List<StoredMessage>): Selection {
        var used = 0
        var firstKept = messages.size
        for (index in messages.indices.reversed()) {
            val cost = estimator.estimate(messages[index].text)
            val fitsBudget = used + cost <= budgetTokens
            val fitsCount = messages.size - index <= maxPairs * 2
            if (!fitsBudget || !fitsCount) break
            used += cost
            firstKept = index
        }
        // Окно начинается с вопроса пользователя: ответ без вопроса перед ним только запутывает модель.
        while (firstKept < messages.size && messages[firstKept].author == Author.ASSISTANT) firstKept++
        return Selection(kept = messages.subList(firstKept, messages.size), overflow = messages.subList(0, firstKept))
    }
}
