package shuman.bsu.famcs.rag.knowledge.store

import shuman.bsu.famcs.rag.knowledge.model.IndexedDocument

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
