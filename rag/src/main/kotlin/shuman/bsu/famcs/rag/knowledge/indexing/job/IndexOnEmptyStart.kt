package shuman.bsu.famcs.rag.knowledge.indexing.job

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseReader
import shuman.bsu.famcs.rag.platform.runtime.ApplicationScope
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

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
