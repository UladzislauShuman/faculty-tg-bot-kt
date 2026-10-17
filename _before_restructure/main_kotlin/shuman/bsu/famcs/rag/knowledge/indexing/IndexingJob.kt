package shuman.bsu.famcs.rag.knowledge.indexing

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import shuman.bsu.famcs.rag.app.ApplicationScope
import shuman.bsu.famcs.rag.core.AdminNotifier
import shuman.bsu.famcs.rag.knowledge.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

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

/** Ночная индексация по расписанию из конфигурации. */
@Component
class NightlyIndexing(
    private val job: IndexingJob,
) {
    @Scheduled(cron = "\${bot.knowledge.indexing.cron}", zone = "\${bot.time-zone}")
    fun runNightly() {
        job.start(IndexMode.INCREMENTAL)
    }
}

/** При первом запуске, когда база знаний пуста, сразу запускает индексацию, чтобы бот не отвечал «не знаю» целые сутки. */
@Component
class IndexOnEmptyStart(
    private val reader: KnowledgeBaseReader,
    private val job: IndexingJob,
    private val settings: KnowledgeProperties,
    private val scope: ApplicationScope,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!settings.indexing.indexOnEmptyStart) return
        scope.launchTask("check-knowledge-base") {
            val count = try {
                reader.chunkCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Коллекции ещё нет (первый запуск) или Qdrant недоступен: считаем базу пустой.
                log.info { "Не удалось прочитать размер базы знаний (${e.message}): считаем её пустой" }
                0L
            }
            if (count == 0L) {
                log.info { "База знаний пуста: запускаем индексацию" }
                job.start(IndexMode.INCREMENTAL)
            } else {
                log.info { "База знаний готова: $count фрагментов" }
            }
        }
    }
}
