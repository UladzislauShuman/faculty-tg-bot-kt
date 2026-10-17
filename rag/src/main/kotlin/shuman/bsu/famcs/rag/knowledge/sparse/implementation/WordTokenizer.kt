package shuman.bsu.famcs.rag.knowledge.sparse.implementation

import java.util.Locale

/** Делит текст на слова: максимальные последовательности букв, цифр и подчёркиваний. Регистр понижается. */
internal object WordTokenizer {
    /**
     * Делит текст на слова в нижнем регистре.
     *
     * @param text исходный текст.
     * @return слова в порядке следования.
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        text.lowercase(Locale.ROOT).codePoints().forEach { codePoint ->
            if (isWordCodePoint(codePoint)) {
                current.appendCodePoint(codePoint)
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** Совпадает с `\w` Python: буквы, цифры любых алфавитов и подчёркивание. */
    private fun isWordCodePoint(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) ||
            codePoint == '_'.code ||
            Character.getType(codePoint).let { it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt() }
}
