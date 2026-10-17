package shuman.bsu.famcs.rag.knowledge.model

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
