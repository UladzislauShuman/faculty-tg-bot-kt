package shuman.bsu.famcs.rag.knowledge

import java.time.Instant

/*
 * Типы данных модуля знаний: документ из источника, его разбиение на чанки и результаты поиска.
 *
 * Схема «родитель и ребёнок»: поиск идёт по мелким фрагментам (дети, около 300 символов), чтобы точно попасть
 * в смысл вопроса, а в промпт уходит крупный раздел-родитель (около 1200 символов), чтобы модели хватило контекста.
 */

/** Скачанный документ до разбиения. */
data class FetchedDocument(
    /** Адрес документа. Служит идентификатором источника и ссылкой в ответе. */
    val url: String,
    /** Тип содержимого, например `text/html`. По нему выбирается разбиватель. */
    val mediaType: String,
    val body: String,
    val fetchedAt: Instant,
)

/** Крупный раздел документа: уходит в промпт как контекст. */
data class ParentChunk(
    val id: String,
    val text: String,
    /** Заголовки, под которыми лежит раздел (`H1`, `H2`, `H3`). */
    val headings: Map<String, String>,
)

/** Мелкий фрагмент: по нему идёт поиск. */
data class ChildChunk(
    /** Стабильный идентификатор: хеш адреса и текста. Тот же фрагмент даёт тот же идентификатор. */
    val id: String,
    val parentId: String,
    val text: String,
)

/** Документ, разбитый на чанки. */
data class ChunkedDocument(
    val url: String,
    val title: String,
    val parents: List<ParentChunk>,
    val children: List<ChildChunk>,
) {
    fun parentOf(child: ChildChunk): ParentChunk? = parents.firstOrNull { it.id == child.parentId }
}

/** Разреженный вектор: индексы терминов и их веса. */
data class SparseVector(
    val indices: IntArray,
    val values: FloatArray,
) {
    init {
        require(indices.size == values.size) { "Индексов (${indices.size}) и весов (${values.size}) должно быть поровну" }
    }

    override fun equals(other: Any?): Boolean =
        other is SparseVector && indices.contentEquals(other.indices) && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * indices.contentHashCode() + values.contentHashCode()
}
