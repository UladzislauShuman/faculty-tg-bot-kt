package shuman.bsu.famcs.rag.knowledge.chunking.implementation

/**
 * Делит слишком длинный родительский раздел на окна не длиннее заданного размера.
 *
 * Зачем: раздел без внутренних заголовков (хроника года, длинный список) мог занимать до 24 тысяч знаков. Реранкер читает
 * не больше 512 токенов (около 1500–2000 знаков), поэтому оценивал только начало раздела, а в промпт уходил весь раздел.
 * На тестовом наборе родитель до 1500 знаков не ухудшил поиск и сократил промпт втрое. Резать стараются по пустой строке,
 * затем по переводу строки, в крайнем случае по длине.
 */
object ParentWindowSplitter {
    /** Остаток короче этого размера присоединяется к предыдущему окну, чтобы не получались обрывки из одной фразы. */
    private const val MIN_TAIL = 200

    /**
     * Границы окон текста.
     *
     * @param text текст раздела.
     * @param maxChars предельная длина окна в символах; значение не больше нуля отключает деление.
     * @return непрерывные диапазоны индексов, покрывающие весь текст; один диапазон, если делить не нужно.
     */
    fun windows(
        text: String,
        maxChars: Int,
    ): List<IntRange> {
        if (maxChars <= 0 || text.length <= maxChars) return listOf(text.indices)
        val result = mutableListOf<IntRange>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val from = start + maxChars / 2
                val cut = text.lastIndexOf("\n\n", end).takeIf { it >= from } ?: text.lastIndexOf('\n', end).takeIf { it >= from }
                if (cut != null) end = cut + 1
            }
            result += start until end
            start = end
        }
        // Короткий хвост присоединяется к предыдущему окну.
        if (result.size > 1 && result.last().count() < MIN_TAIL) {
            val tail = result.removeLast()
            result[result.lastIndex] = result.last().first..tail.last
        }
        return result
    }

    /**
     * Тексты окон.
     *
     * @param text текст раздела.
     * @param maxChars предельная длина окна; значение не больше нуля отключает деление.
     */
    fun split(
        text: String,
        maxChars: Int,
    ): List<String> = windows(text, maxChars).map { text.substring(it).trim() }.filter { it.isNotEmpty() }
}
