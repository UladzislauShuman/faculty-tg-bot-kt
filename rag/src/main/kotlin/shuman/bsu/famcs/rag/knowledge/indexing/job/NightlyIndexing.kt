package shuman.bsu.famcs.rag.knowledge.indexing.job

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import shuman.bsu.famcs.rag.knowledge.model.IndexMode

/** Ночная индексация по расписанию из конфигурации. */
@Component
class NightlyIndexing(
    private val job: IndexingJob,
) {
    /** Запускает обычное обновление базы знаний по расписанию. */
    @Scheduled(cron = "\${bot.knowledge.indexing.cron}", zone = "\${bot.time-zone}")
    fun runNightly() {
        job.start(IndexMode.INCREMENTAL)
    }
}
