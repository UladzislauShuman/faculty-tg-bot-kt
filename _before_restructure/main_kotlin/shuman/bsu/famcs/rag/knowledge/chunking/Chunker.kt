package shuman.bsu.famcs.rag.knowledge.chunking

import shuman.bsu.famcs.rag.knowledge.ChunkedDocument
import shuman.bsu.famcs.rag.knowledge.FetchedDocument

/**
 * Превращает скачанный документ в родительские и дочерние чанки.
 *
 * Новый формат источника (PDF, DOCX, PPTX) подключается ещё одной реализацией: индексатор выбирает разбиватель
 * по типу содержимого и ничего не знает о форматах.
 */
interface Chunker {
    /** Умеет ли разбиватель работать с этим типом содержимого (например, `text/html`). */
    fun supports(mediaType: String): Boolean

    fun chunk(document: FetchedDocument): ChunkedDocument
}
