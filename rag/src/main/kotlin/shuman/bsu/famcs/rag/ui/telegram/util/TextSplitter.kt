package shuman.bsu.famcs.rag.ui.telegram.util

/**
 * Режет длинный текст на части не длиннее лимита Telegram.
 *
 * Предпочитает границы абзацев, затем строк, затем предложений и слов: ответ не обрывается посреди слова.
 * Если ни одной границы нет (сплошная строка), режет по лимиту.
 */
object TextSplitter {
    private val BOUNDARIES = listOf("\n\n", "\n", ". ", " ")

    /**
     * Делит длинный текст на части не длиннее [limit] символов, стараясь резать по абзацам, строкам и пробелам.
     *
     * @param text исходный текст.
     * @param limit максимальная длина части (у Telegram — 4096).
     * @return части в исходном порядке; пустой текст даёт пустой список.
     */
    fun split(
        text: String,
        limit: Int,
    ): List<String> {
        require(limit > 0) { "Лимит должен быть положительным" }
        val parts = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val cut = cutPoint(rest, limit)
            parts += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /** Позиция разреза: после последней границы в пределах лимита, но не в первой половине (иначе часть получится слишком короткой). */
    private fun cutPoint(
        text: String,
        limit: Int,
    ): Int {
        for (boundary in BOUNDARIES) {
            val index = text.lastIndexOf(boundary, startIndex = limit - boundary.length)
            if (index > limit / 2) return index + boundary.length
        }
        return limit
    }
}
