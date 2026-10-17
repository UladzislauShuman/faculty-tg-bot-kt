package shuman.bsu.famcs.rag.knowledge.indexing.job

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.knowledge.indexing.IndexRunner
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.model.IndexReport
import shuman.bsu.famcs.rag.knowledge.model.IndexRunStatus
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import shuman.bsu.famcs.rag.platform.runtime.ApplicationScope
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/**
 * Запускает индексацию в фоне и следит, чтобы одновременно шла только одна.
 *
 * Две индексации разом означали бы двойную нагрузку на процессор и гонку записи в одни и те же страницы.
 */
class IndexingJob(
    private val indexer: IndexRunner,
    private val scope: ApplicationScope,
    private val notifier: AdminNotifier,
) {
    private val running = AtomicBoolean(false)

    /**
     * Запускает индексацию в фоне. Возвращает `false`, если предыдущая ещё не закончилась.
     * Администраторам всегда сообщается о неудаче; об успехе — только если [reportSuccess] (запуск вручную): ночной успех не шумит.
     */
    fun start(
        mode: IndexMode,
        reportSuccess: Boolean = false,
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            log.warn { "Индексация ($mode) не запущена: предыдущая ещё идёт" }
            return false
        }
        scope.launchTask("indexing-$mode") {
            try {
                report(indexer.run(mode), reportSuccess)
            } finally {
                running.set(false)
            }
        }
        return true
    }

    /**
     * Отправляет администраторам итог индексации. Успешные запуски по расписанию молчат, сбои сообщаются всегда.
     *
     * @param report отчёт о запуске.
     * @param reportSuccess сообщать ли об успехе (при ручном запуске — да).
     */
    private suspend fun report(
        report: IndexReport,
        reportSuccess: Boolean,
    ) {
        if (report.status == IndexRunStatus.SUCCEEDED && !reportSuccess) return
        val counters = report.counters
        notifier.notifyAdmins(
            "Индексация #${report.runId} (${report.mode}): ${report.status}. Найдено страниц ${counters.found}, " +
                "изменено ${counters.changed}, ошибок ${counters.failed}, удалено ${counters.removed}." +
                (report.message?.let { " $it" } ?: ""),
        )
    }
}
