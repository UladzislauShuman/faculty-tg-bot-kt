package shuman.bsu.famcs.rag.knowledge.model

/** Документ, разбитый на чанки. */
data class ChunkedDocument(
    val url: String,
    val title: String,
    val parents: List<ParentChunk>,
    val children: List<ChildChunk>,
    /** Тип страницы (основной раздел, новость, хроника, перечень…). */
    val pageType: PageType = PageType.SECTION,
    /** Год страницы по адресу или заголовку; `null`, если неизвестен. */
    val year: Int? = null,
) {
    /**
     * Находит раздел-родитель фрагмента.
     *
     * @param child фрагмент.
     * @return родитель или `null`, если связь нарушена.
     */
    fun parentOf(child: ChildChunk): ParentChunk? = parents.firstOrNull { it.id == child.parentId }
}
