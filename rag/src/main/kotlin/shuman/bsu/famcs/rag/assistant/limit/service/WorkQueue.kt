package shuman.bsu.famcs.rag.assistant.limit.service

import shuman.bsu.famcs.rag.assistant.limit.exception.OverloadedException

/** Ограниченная очередь работы: место либо выдаётся, либо вопрос получает [OverloadedException]. */
interface WorkQueue {
    /**
     * Ждёт свободное место в очереди ограниченное время, выполняет [block] и освобождает место.
     *
     * @param block работа над вопросом.
     * @return результат [block].
     * @throws shuman.bsu.famcs.rag.assistant.limit.exception.OverloadedException если очередь полна или место не освободилось вовремя.
     */
    suspend fun <T> withSlot(block: suspend () -> T): T
}
