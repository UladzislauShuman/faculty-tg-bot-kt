package shuman.bsu.famcs.rag.knowledge.indexing

import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.model.IndexReport

/** Запуск индексации. Выделен интерфейсом, чтобы фоновое задание проверялось без настоящего индексатора. */
fun interface IndexRunner {
    /**
     * Запускает индексацию и ждёт её окончания.
     *
     * @param mode обычное обновление или полная пересборка.
     * @return отчёт: статус и счётчики страниц.
     */
    suspend fun run(mode: IndexMode): IndexReport
}
