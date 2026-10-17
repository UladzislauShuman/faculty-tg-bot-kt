package shuman.bsu.famcs.rag.knowledge.store

import shuman.bsu.famcs.rag.knowledge.model.HybridSearchRequest
import shuman.bsu.famcs.rag.knowledge.model.SearchHit

/**
 * Чтение знаний: гибридный поиск по активной коллекции.
 *
 * Хранилище скрыто за интерфейсами (чтение, запись, управление коллекциями), чтобы они подменялись независимо
 * (другая векторная база, графовый поиск, тестовая заглушка), а остальной код не знал про Qdrant.
 */
interface KnowledgeBaseReader {
    /**
     * Гибридный поиск по активной коллекции: смысловой и по словам, результаты объединяются на стороне хранилища.
     *
     * @param request векторы запроса и параметры поиска.
     * @return найденные фрагменты по убыванию общей оценки.
     */
    suspend fun search(request: HybridSearchRequest): List<SearchHit>

    /** Сколько фрагментов в активной коллекции. Пустая база — признак того, что индексация не выполнена. */
    suspend fun chunkCount(): Long
}
