package shuman.bsu.famcs.rag.eval

/**
 * Грубая мера «нужное есть в найденном»: доля значимых слов эталонного ответа, найденных в тексте разделов.
 * Не зависит от разметки страниц, но считает слова, а не смысл: это приближение для разделения вины между поиском и генерацией.
 */
object AnswerCoverage {
    /** Слова короче или равные этой длины не считаются значимыми (предлоги, союзы). */
    private const val MIN_LENGTH = 4

    /**
     * Считает долю слов эталона, найденных в контексте.
     *
     * @param reference эталонный ответ.
     * @param context объединённый текст найденных разделов.
     * @return число от 0 до 1; 1, если в эталоне нет значимых слов.
     */
    fun share(
        reference: String,
        context: String,
    ): Double {
        val words = words(reference)
        if (words.isEmpty()) return 1.0
        val haystack = words(context)
        return words.count { it in haystack }.toDouble() / words.size
    }

    /** Множество значимых слов текста в нижнем регистре, «ё» как «е». */
    private fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase().replace('ё', 'е'))
        .map { it.value }
        .filter { it.length >= MIN_LENGTH }
        .toSet()
}
