package shuman.bsu.famcs.rag.knowledge.model

/** Мелкий фрагмент (около 300 символов): по нему идёт поиск. Принадлежит разделу-родителю [ParentChunk]. */
data class ChildChunk(
    /** Стабильный идентификатор: хеш адреса и текста. Тот же фрагмент даёт тот же идентификатор. */
    val id: String,
    val parentId: String,
    val text: String,
)
