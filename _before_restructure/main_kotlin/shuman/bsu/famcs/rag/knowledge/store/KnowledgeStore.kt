package shuman.bsu.famcs.rag.knowledge.store

import shuman.bsu.famcs.rag.knowledge.SparseVector
import java.time.Instant

/*
 * Интерфейсы хранилища знаний и данные, которыми они обмениваются.
 *
 * Хранилище скрыто за интерфейсами, чтобы поиск, запись и управление коллекциями подменялись независимо
 * (другая векторная база, графовый поиск, тестовая заглушка), а остальной код не знал про Qdrant.
 */

/** Один фрагмент, готовый к записи: текст, родитель и оба вектора. */
class IndexedChunk(
    val id: String,
    val text: String,
    val parentId: String,
    /** Текст родительского раздела хранится вместе с ребёнком: родитель читается одним запросом вместе с поиском. */
    val parentText: String,
    /** Заголовки раздела (`H1`, `H2`, `H3`). */
    val headings: Map<String, String>,
    val dense: FloatArray,
    val sparse: SparseVector,
)

/** Все фрагменты одной страницы. Запись заменяет прежнюю версию страницы целиком. */
class IndexedDocument(
    val url: String,
    val title: String,
    val indexedAt: Instant,
    /** Номер версии (запуска индексации). По нему удаляются фрагменты прежней версии той же страницы. */
    val version: Long,
    val chunks: List<IndexedChunk>,
)

/** Запрос гибридного поиска: вектор по смыслу и вектор по ключевым словам. */
class HybridSearchRequest(
    val dense: FloatArray,
    val sparse: SparseVector,
)

/** Найденный фрагмент с родителем и сведениями об источнике. */
data class SearchHit(
    val id: String,
    /** Оценка после слияния. Только для сравнения внутри одного ответа, не абсолютная мера. */
    val score: Double,
    val text: String,
    val parentId: String,
    val parentText: String,
    val url: String,
    val title: String,
    val headings: Map<String, String>,
    val indexedAt: Instant?,
)

/** Чтение знаний: гибридный поиск по активной коллекции. */
interface KnowledgeBaseReader {
    suspend fun search(request: HybridSearchRequest): List<SearchHit>

    /** Сколько фрагментов в активной коллекции. Пустая база — признак того, что индексация не выполнена. */
    suspend fun chunkCount(): Long
}

/** Запись знаний. */
interface KnowledgeBaseWriter {
    /**
     * Заменяет страницу: записывает новую версию, затем удаляет фрагменты прежней версии той же страницы.
     * Порядок «сначала запись, потом удаление» не оставляет окна, в котором страницы нет.
     *
     * @param collection физическая коллекция; `null` — активная.
     */
    suspend fun replaceDocument(
        collection: String?,
        document: IndexedDocument,
    )

    /** Удаляет все фрагменты страницы (страница пропала с сайта). */
    suspend fun deleteDocument(
        collection: String?,
        url: String,
    )
}

/** Жизненный цикл коллекций: создание, переключение активной, удаление старых. */
interface KnowledgeCollections {
    /** Физическая коллекция, на которую сейчас указывает алиас. `null`, если алиаса нет. */
    suspend fun active(): String?

    /** Создаёт пустую коллекцию со схемой и возвращает её имя. */
    suspend fun create(): String

    /** Гарантирует, что активная коллекция есть: при первом запуске создаёт пустую и направляет на неё алиас. */
    suspend fun ensureActive(): String

    /** Атомарно переключает алиас на [collection]: поиск мгновенно видит новую коллекцию. */
    suspend fun activate(collection: String)

    /** Удаляет старые коллекции, оставляя активную и несколько последних для отката. */
    suspend fun dropOld()

    /** Удаляет [collection], которую не удалось довести до рабочей (прерванная пересборка). Активную коллекцию не удаляет. */
    suspend fun discard(collection: String)
}
