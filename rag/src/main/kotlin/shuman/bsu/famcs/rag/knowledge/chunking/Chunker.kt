package shuman.bsu.famcs.rag.knowledge.chunking

import shuman.bsu.famcs.rag.knowledge.model.ChunkedDocument
import shuman.bsu.famcs.rag.knowledge.model.FetchedDocument

/**
 * Превращает скачанный документ в родительские и дочерние чанки.
 *
 * Новый формат источника (PDF, DOCX, PPTX) подключается ещё одной реализацией: индексатор выбирает разбиватель
 * по типу содержимого и ничего не знает о форматах.
 */
interface Chunker {
    /** Умеет ли разбиватель работать с этим типом содержимого (например, `text/html`). */
    fun supports(mediaType: String): Boolean

    /**
     * Нарезает документ на разделы-родители и фрагменты-дети.
     *
     * @param document документ, загруженный из источника.
     * @return родители (крупные разделы для промпта) и дети (мелкие фрагменты для поиска).
     */
    fun chunk(document: FetchedDocument): ChunkedDocument
}
