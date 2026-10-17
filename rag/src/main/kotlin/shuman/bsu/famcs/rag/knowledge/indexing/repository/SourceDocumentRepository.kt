package shuman.bsu.famcs.rag.knowledge.indexing.repository

import shuman.bsu.famcs.rag.knowledge.model.KnownDocument
import java.time.Instant

/** Учёт проиндексированных страниц: по нему индексатор понимает, что изменилось с прошлого раза. */
interface SourceDocumentRepository {
    /** Все известные страницы по адресам: по ним видно, что изменилось и что исчезло с сайта. */
    suspend fun all(): Map<String, KnownDocument>

    /**
     * Сохраняет сведения о проиндексированной странице.
     *
     * @param url адрес страницы.
     * @param title заголовок.
     * @param contentHash хеш содержимого.
     * @param version версия страницы в коллекции.
     * @param chunkCount сколько фрагментов получено.
     * @param checkedAt момент проверки.
     */
    suspend fun save(
        url: String,
        title: String,
        contentHash: String,
        version: Long,
        chunkCount: Int,
        checkedAt: Instant,
    )

    /** Страница проверена и не изменилась: обновляется только дата проверки. */
    suspend fun touch(
        url: String,
        checkedAt: Instant,
    )

    /**
     * Удаляет сведения о странице, которой больше нет на сайте.
     *
     * @param url адрес страницы.
     */
    suspend fun delete(url: String)
}
