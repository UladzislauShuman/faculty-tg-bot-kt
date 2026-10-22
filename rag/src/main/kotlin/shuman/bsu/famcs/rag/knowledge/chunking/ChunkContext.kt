package shuman.bsu.famcs.rag.knowledge.chunking

/**
 * Контекстная строка фрагмента: заголовок страницы и раздела.
 *
 * Мелкий фрагмент в 300 знаков не знает, из какой он страницы («в 1997 году избран заведующий…» без слов «История 1997»).
 * Перед расчётом векторов и словарного индекса к нему приписывается эта строка (приём Contextual Retrieval). В ответ и в промпт
 * идёт чистый текст фрагмента без неё.
 */
object ChunkContext {
    /**
     * Название страницы без хвоста с названием сайта: «История 1997 | Факультет прикладной математики…» → «История 1997».
     *
     * @param title заголовок страницы из тега `title`.
     */
    fun shortTitle(title: String): String = title.substringBefore('|').trim()

    /**
     * Строка контекста: название страницы и заголовки раздела, если они есть и отличаются от названия страницы.
     *
     * @param title заголовок страницы.
     * @param headings заголовки раздела (`H1`, `H2`, `H3`).
     * @return строка вида «Название страницы. Раздел: Заголовок».
     */
    fun line(
        title: String,
        headings: Map<String, String>,
    ): String {
        val page = shortTitle(title)
        val section = listOf("H1", "H2", "H3").mapNotNull { headings[it]?.trim()?.takeIf(String::isNotEmpty) }.filter { it != page }
        return if (section.isEmpty()) page else "$page. Раздел: ${section.joinToString(" / ")}"
    }

    /**
     * Текст для расчёта векторов: контекстная строка и текст фрагмента.
     *
     * @param title заголовок страницы.
     * @param headings заголовки раздела.
     * @param text текст фрагмента.
     */
    fun searchText(
        title: String,
        headings: Map<String, String>,
        text: String,
    ): String = "${line(title, headings)}\n$text"
}
