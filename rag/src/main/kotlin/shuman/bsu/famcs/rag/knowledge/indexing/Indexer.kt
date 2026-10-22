package shuman.bsu.famcs.rag.knowledge.indexing

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import shuman.bsu.famcs.rag.knowledge.chunking.ChunkContext
import shuman.bsu.famcs.rag.knowledge.chunking.Chunker
import shuman.bsu.famcs.rag.knowledge.configuration.properties.KnowledgeProperties
import shuman.bsu.famcs.rag.knowledge.embedding.EmbeddingProvider
import shuman.bsu.famcs.rag.knowledge.indexing.repository.IndexRunRepository
import shuman.bsu.famcs.rag.knowledge.indexing.repository.SourceDocumentRepository
import shuman.bsu.famcs.rag.knowledge.model.ChunkedDocument
import shuman.bsu.famcs.rag.knowledge.model.FetchResult
import shuman.bsu.famcs.rag.knowledge.model.FetchedDocument
import shuman.bsu.famcs.rag.knowledge.model.IndexCounters
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.knowledge.model.IndexReport
import shuman.bsu.famcs.rag.knowledge.model.IndexRunStatus
import shuman.bsu.famcs.rag.knowledge.model.IndexedChunk
import shuman.bsu.famcs.rag.knowledge.model.IndexedDocument
import shuman.bsu.famcs.rag.knowledge.model.KnownDocument
import shuman.bsu.famcs.rag.knowledge.source.DataSource
import shuman.bsu.famcs.rag.knowledge.sparse.Bm25SparseEncoder
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeBaseWriter
import shuman.bsu.famcs.rag.knowledge.store.KnowledgeCollections
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * Обновляет базу знаний из источников.
 *
 * Два режима. Обычный (INCREMENTAL): переписываются только страницы, у которых изменился хеш текста, остальным обновляется
 * дата проверки. Полный (FULL): все страницы пишутся в новую коллекцию, и только после успеха алиас переключается на неё
 * (смена модели эмбеддингов, смена нарезки).
 *
 * Защита от поломки: если страниц найдено подозрительно мало или слишком много ошибок, обновление не применяется и бот
 * продолжает отвечать по прежним данным. Удалённой считается только страница, которую сайт явно назвал несуществующей (404, 410).
 */
class Indexer(
    private val sources: List<DataSource>,
    private val chunkers: List<Chunker>,
    private val embeddings: EmbeddingProvider,
    private val sparse: Bm25SparseEncoder,
    private val writer: KnowledgeBaseWriter,
    private val collections: KnowledgeCollections,
    private val documents: SourceDocumentRepository,
    private val runs: IndexRunRepository,
    private val settings: KnowledgeProperties.Indexing,
    private val clock: Clock,
    private val meters: MeterRegistry,
    /** Приписывать название страницы и раздела к тексту фрагмента перед расчётом векторов (контекст фрагмента). */
    private val contextInSearchText: Boolean = false,
) : IndexRunner {
    private enum class PageOutcome { CHANGED, UNCHANGED, SKIPPED, FAILED }

    /** Запись об изученной странице, отложенная до переключения алиаса (только полная пересборка). */
    private class PendingSave(
        val url: String,
        val title: String,
        val hash: String,
        val version: Long,
        val chunkCount: Int,
        val checkedAt: Instant,
    )

    /** Страницы, пропавшие из обхода: подтверждённо удалённые (404, 410) и неопределённые (могли быть недоступны). */
    private class GonePages(
        val confirmed: Set<String>,
        val unconfirmed: Set<String>,
    )

    override suspend fun run(mode: IndexMode): IndexReport {
        val runId = runs.start(mode, clock.instant())
        log.info { "Индексация #$runId ($mode) началась" }
        return try {
            execute(runId, mode)
        } catch (e: CancellationException) {
            finish(runId, mode, IndexRunStatus.ABORTED, EMPTY, "остановлена вместе с приложением")
            throw e
        } catch (e: Exception) {
            log.error(e) { "Индексация #$runId завершилась ошибкой" }
            finish(runId, mode, IndexRunStatus.FAILED, EMPTY, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Основной ход индексации: снимок источников, защита от аварийного обхода, обработка страниц, удаление пропавших,
     * переключение коллекции (для полной пересборки).
     *
     * @param runId номер запуска в журнале.
     * @param mode режим индексации.
     * @return отчёт о запуске.
     */
    private suspend fun execute(
        runId: Long,
        mode: IndexMode,
    ): IndexReport {
        val snapshots = sources.map { it to it.snapshot() }
        val found = snapshots.flatMap { it.second.documents }
        val failedUrls = snapshots.flatMap { it.second.failed }
        val known = documents.all()

        abortReason(found.size, failedUrls.size, known.size)?.let { reason ->
            log.warn { "Индексация #$runId не применена: $reason" }
            return finish(runId, mode, IndexRunStatus.ABORTED, IndexCounters(found.size, 0, failedUrls.size, 0), reason)
        }

        val target = if (mode == IndexMode.FULL) collections.create() else collections.ensureActive()
        var activated = false
        try {
            val pending = ConcurrentLinkedQueue<PendingSave>()
            val gate = Semaphore(settings.parallelPages)
            val progress = IndexProgress(runId, found.size)
            log.info { "Индексация #$runId: страниц к обработке ${found.size}, ошибок скачивания ${failedUrls.size}" }
            val outcomes = coroutineScope {
                found
                    .map { doc ->
                        async {
                            gate.withPermit { processPage(doc, mode, known[doc.url], target, runId, pending) }.also {
                                progress.record(
                                    changed =
                                        it == PageOutcome.CHANGED,
                                )
                            }
                        }
                    }.awaitAll()
            }
            val changed = outcomes.count { it == PageOutcome.CHANGED }
            val failed = failedUrls.size + outcomes.count { it == PageOutcome.FAILED }
            val gone = findGonePages(known.keys, found, failedUrls, snapshots.flatMap { it.second.notFound })

            if (mode == IndexMode.FULL) {
                // Новая коллекция должна быть полной: пропажа известной страницы означала бы молчаливую потерю знаний.
                val skippedKnown = found.zip(outcomes).count { (doc, outcome) -> outcome == PageOutcome.SKIPPED && doc.url in known }
                val incomplete = failed + gone.unconfirmed.size + skippedKnown
                if (incomplete > 0) {
                    val reason = "полная пересборка отменена: неполный снимок сайта ($incomplete страниц не обработано " +
                        "или не подтверждено), алиас не переключён"
                    return finish(runId, mode, IndexRunStatus.ABORTED, IndexCounters(found.size, changed, failed, 0), reason)
                }
                collections.activate(target)
                activated = true
                // Учёт страниц обновляется только теперь: пока алиас не переключён, в рабочей коллекции прежние версии.
                pending.forEach { documents.save(it.url, it.title, it.hash, it.version, it.chunkCount, it.checkedAt) }
                gone.confirmed.forEach { documents.delete(it) }
                collections.dropOld()
            } else {
                gone.confirmed.forEach { url ->
                    writer.deleteDocument(target, url)
                    documents.delete(url)
                }
            }
            return finish(runId, mode, IndexRunStatus.SUCCEEDED, IndexCounters(found.size, changed, failed, gone.confirmed.size), null)
        } finally {
            // Прерванная пересборка не должна оставлять недостроенную коллекцию: она заняла бы слот отката в dropOld.
            if (mode == IndexMode.FULL && !activated) {
                withContext(NonCancellable) { discardQuietly(target) }
            }
        }
    }

    /** Причина, по которой обновление применять нельзя. `null` — можно. */
    private fun abortReason(
        found: Int,
        failed: Int,
        previous: Int,
    ): String? {
        val attempted = found + failed
        return when {
            found == 0 -> {
                "не найдено ни одной страницы"
            }

            attempted > 0 && failed.toDouble() / attempted > settings.maxFailedShare -> {
                "слишком много ошибок при скачивании: $failed из $attempted (предел ${settings.maxFailedShare})"
            }

            previous > 0 && found < previous * settings.minPagesShareOfPrevious -> {
                "страниц найдено $found, а в прошлый раз было $previous: сайт мог быть недоступен частично"
            }

            else -> {
                null
            }
        }
    }

    /**
     * Обрабатывает одну страницу: нарезка, сравнение хеша с прежним, расчёт векторов и запись в коллекцию.
     * Сбой одной страницы не останавливает индексацию.
     *
     * @param doc страница из источника.
     * @param mode режим индексации.
     * @param previous прежняя запись об этой странице или `null`, если она новая.
     * @param target имя коллекции, в которую пишется результат.
     * @param runId номер запуска в журнале.
     * @param pending отложенные сохранения (при полной пересборке они фиксируются после переключения коллекции).
     */
    private suspend fun processPage(
        doc: FetchedDocument,
        mode: IndexMode,
        previous: KnownDocument?,
        target: String,
        runId: Long,
        pending: MutableCollection<PendingSave>,
    ): PageOutcome = try {
        val chunker = chunkers.firstOrNull { it.supports(doc.mediaType) }
        val chunked = chunker?.chunk(doc)
        if (chunked == null || chunked.children.isEmpty()) {
            // Страница без основного содержимого: при обычном обновлении прежняя версия, если была, остаётся в индексе.
            log.warn { "Нет содержимого для индекса: ${doc.url}" }
            PageOutcome.SKIPPED
        } else {
            val hash = contentHash(chunked)
            if (mode == IndexMode.INCREMENTAL && previous?.contentHash == hash) {
                documents.touch(doc.url, clock.instant())
                PageOutcome.UNCHANGED
            } else {
                writer.replaceDocument(target, toIndexed(chunked, runId))
                val save = PendingSave(doc.url, chunked.title, hash, runId, chunked.children.size, clock.instant())
                if (mode ==
                    IndexMode.FULL
                ) {
                    pending += save
                } else {
                    documents.save(save.url, save.title, save.hash, save.version, save.chunkCount, save.checkedAt)
                }
                PageOutcome.CHANGED
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "Не удалось проиндексировать ${doc.url}" }
        PageOutcome.FAILED
    }

    /**
     * Считает векторы фрагментов и собирает документ для записи в хранилище.
     *
     * @param chunked нарезанный документ.
     * @param version метка версии (номер запуска): по ней старые фрагменты страницы отличаются от новых.
     */
    private suspend fun toIndexed(
        chunked: ChunkedDocument,
        version: Long,
    ): IndexedDocument {
        // Для поиска к фрагменту приписывается контекст страницы, в данных и в ответе остаётся чистый текст.
        val searchTexts = chunked.children.map { child ->
            val parent = requireNotNull(chunked.parentOf(child)) { "У фрагмента ${child.id} нет родителя" }
            if (contextInSearchText) {
                ChunkContext.searchText(
                    chunked.title,
                    child.headings.ifEmpty { parent.headings },
                    child.text,
                )
            } else {
                child.text
            }
        }
        val dense = embeddings.embedDocuments(searchTexts)
        val chunks = chunked.children.mapIndexed { index, child ->
            val parent = requireNotNull(chunked.parentOf(child)) { "У фрагмента ${child.id} нет родителя" }
            IndexedChunk(
                id = child.id,
                text = child.text,
                parentId = parent.id,
                parentText = parent.text,
                headings = child.headings.ifEmpty { parent.headings },
                dense = dense[index],
                sparse = sparse.encodeDocument(searchTexts[index]),
            )
        }
        return IndexedDocument(
            chunked.url,
            chunked.title,
            clock.instant(),
            version,
            chunks,
            chunked.pageType,
            chunked.year,
            chunked.published,
            chunked.orphan,
        )
    }

    /**
     * Разбирает страницы, которых нет в обходе. Подтверждённо удалены те, что сайт назвал несуществующими (404, 410) или
     * которые при прямой проверке оказались несуществующими: ссылку могли убрать, а сама страница жива. Остальные неопределённые.
     */
    private suspend fun findGonePages(
        knownUrls: Set<String>,
        found: List<FetchedDocument>,
        failedUrls: List<String>,
        notFoundUrls: List<String>,
    ): GonePages {
        val foundUrls = found.map { it.url }.toSet()
        val explicitlyGone = knownUrls.intersect(notFoundUrls.toSet())
        val missing = knownUrls - foundUrls - failedUrls.toSet() - explicitlyGone
        val confirmedGone = missing.filter { url -> sources.any { it.fetch(url) is FetchResult.NotFound } }.toSet()
        return GonePages(confirmed = explicitlyGone + confirmedGone, unconfirmed = missing - confirmedGone)
    }

    /**
     * Удаляет недособранную коллекцию; сбой удаления только журналируется, чтобы не затенить исходную ошибку.
     *
     * @param collection имя коллекции.
     */
    private suspend fun discardQuietly(collection: String) {
        try {
            collections.discard(collection)
        } catch (e: Exception) {
            log.warn(e) { "Не удалось удалить незавершённую коллекцию $collection" }
        }
    }

    /**
     * Закрывает запуск в журнале и возвращает отчёт. Выполняется даже при отмене, чтобы запись не осталась «идёт».
     *
     * @param runId номер запуска.
     * @param mode режим.
     * @param status итоговый статус.
     * @param counters счётчики страниц.
     * @param message пояснение для администратора.
     */
    private suspend fun finish(
        runId: Long,
        mode: IndexMode,
        status: IndexRunStatus,
        counters: IndexCounters,
        message: String?,
    ): IndexReport = withContext(NonCancellable) {
        runs.finish(runId, status, counters, message, clock.instant())
        meters.counter("index.runs", "mode", mode.name.lowercase(), "status", status.name.lowercase()).increment()
        log.info {
            "Индексация #$runId ($mode): $status, страниц ${counters.found}, изменено ${counters.changed}, ошибок ${counters.failed}, удалено ${counters.removed}"
        }
        IndexReport(runId, mode, status, counters, message)
    }

    /**
     * Хеш содержимого страницы (заголовок и все разделы): по нему видно, что страница изменилась.
     *
     * @param chunked нарезанный документ.
     * @return SHA-256 в шестнадцатеричной записи.
     */
    private fun contentHash(chunked: ChunkedDocument): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Версия схемы и тип страницы входят в хеш: смена того, чего нет в тексте страницы, тоже требует пересчёта.
        digest.update(settings.schemaVersion.toByteArray(Charsets.UTF_8))
        digest.update(chunked.pageType.name.toByteArray(Charsets.UTF_8))
        digest.update((chunked.published?.toString() ?: "").toByteArray(Charsets.UTF_8))
        digest.update(if (chunked.orphan) 1 else 0)
        digest.update(chunked.title.toByteArray(Charsets.UTF_8))
        chunked.parents.forEach { parent ->
            digest.update(0)
            digest.update(parent.text.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val EMPTY = IndexCounters(0, 0, 0, 0)
    }
}

/**
 * Прогресс обработки страниц для журнала: раз в [STEP] страниц и на последней пишет, сколько обработано, сколько изменилось,
 * сколько прошло времени и сколько примерно осталось. Страницы обрабатываются параллельно, поэтому счётчики атомарные.
 *
 * @property runId номер запуска.
 * @property total число страниц.
 */
private class IndexProgress(
    private val runId: Long,
    private val total: Int,
) {
    private val started = TimeSource.Monotonic.markNow()
    private val done = AtomicInteger()
    private val changed = AtomicInteger()

    /**
     * Учитывает обработанную страницу.
     *
     * @param changed страница записана заново (новая или изменённая).
     */
    fun record(changed: Boolean) {
        if (changed) this.changed.incrementAndGet()
        val count = done.incrementAndGet()
        if (count % STEP != 0 && count != total) return
        val elapsed = started.elapsedNow().inWholeSeconds
        val remaining = elapsed * (total - count) / count
        log.info {
            "Индексация #$runId: обработано $count из $total, изменено ${this.changed.get()}, " +
                "прошло ${elapsed / 60} мин ${elapsed % 60} с, " +
                "осталось ≈ ${remaining / 60} мин"
        }
    }

    private companion object {
        /** Через сколько страниц писать строку прогресса. */
        const val STEP = 25
    }
}
