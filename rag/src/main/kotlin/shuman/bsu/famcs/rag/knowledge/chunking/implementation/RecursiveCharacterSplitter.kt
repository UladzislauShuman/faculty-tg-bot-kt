package shuman.bsu.famcs.rag.knowledge.chunking.implementation

/**
 * Режет текст на куски не длиннее [chunkSize] символов с перекрытием [chunkOverlap].
 *
 * Порт `RecursiveCharacterTextSplitter` из LangChain (Python) с теми же правилами: берётся первый из разделителей,
 * который есть в тексте; слишком длинные части режутся следующим разделителем рекурсивно; короткие склеиваются.
 * Разделитель остаётся в начале следующего куска (режим `keep_separator=start` в оригинале).
 *
 * Зачем порт: размер и границы дочерних чанков определяют качество поиска, и baseline работал именно с таким разбиением.
 */
class RecursiveCharacterSplitter(
    /** Максимальная длина куска, символы. */
    private val chunkSize: Int,
    /** Сколько символов конца куска повторяется в начале следующего, чтобы не терять мысль на границе. */
    private val chunkOverlap: Int,
    /** Разделители от крупного к мелкому. Пустая строка в конце означает «резать по символам». */
    private val separators: List<String>,
) {
    init {
        require(chunkSize > 0) { "chunkSize должен быть положительным" }
        require(chunkOverlap in 0..chunkSize) { "chunkOverlap ($chunkOverlap) должен быть в пределах 0..chunkSize ($chunkSize)" }
        require(separators.isNotEmpty()) { "Нужен хотя бы один разделитель" }
    }

    /**
     * Делит текст на куски не длиннее заданного размера, пробуя разделители от крупного к мелкому (абзац, строка, слово, символ).
     *
     * @param text текст раздела.
     * @return куски с заданным перекрытием.
     */
    fun split(text: String): List<String> = splitText(text, separators)

    /**
     * Рекурсивное деление: режет по первому подходящему разделителю, слишком длинные куски делит следующими разделителями.
     *
     * @param text текст.
     * @param currentSeparators разделители, которые ещё можно применять.
     */
    private fun splitText(
        text: String,
        currentSeparators: List<String>,
    ): List<String> {
        val result = mutableListOf<String>()

        // Первый разделитель, который встречается в тексте. Если ни одного нет, режем по символам.
        var separator = currentSeparators.last()
        var remaining = emptyList<String>()
        for ((index, candidate) in currentSeparators.withIndex()) {
            if (candidate.isEmpty()) {
                separator = candidate
                break
            }
            if (text.contains(candidate)) {
                separator = candidate
                remaining = currentSeparators.drop(index + 1)
                break
            }
        }

        val pending = mutableListOf<String>()
        for (piece in splitKeepingSeparatorAtStart(text, separator)) {
            if (piece.length < chunkSize) {
                pending += piece
                continue
            }
            if (pending.isNotEmpty()) {
                result += mergePieces(pending)
                pending.clear()
            }
            if (remaining.isEmpty()) result += piece else result += splitText(piece, remaining)
        }
        if (pending.isNotEmpty()) result += mergePieces(pending)
        return result
    }

    /** Делит по разделителю, оставляя его в начале следующей части. Пустые части отбрасываются. */
    private fun splitKeepingSeparatorAtStart(
        text: String,
        separator: String,
    ): List<String> {
        if (separator.isEmpty()) return text.map { it.toString() }
        val pieces = mutableListOf<String>()
        var from = 0
        var found = text.indexOf(separator, from)
        // Часть до первого разделителя идёт без него, каждая следующая начинается с разделителя.
        var pieceStart = 0
        while (found >= 0) {
            pieces += text.substring(pieceStart, found)
            pieceStart = found
            from = found + separator.length
            found = text.indexOf(separator, from)
        }
        pieces += text.substring(pieceStart)
        return pieces.filter { it.isNotEmpty() }
    }

    /** Склеивает короткие части в куски до [chunkSize], оставляя перекрытие [chunkOverlap]. Разделитель уже внутри частей. */
    private fun mergePieces(pieces: List<String>): List<String> {
        val documents = mutableListOf<String>()
        val current = ArrayDeque<String>()
        var total = 0
        for (piece in pieces) {
            val length = piece.length
            if (total + length > chunkSize) {
                if (current.isNotEmpty()) {
                    joinTrimmed(current)?.let(documents::add)
                    // Убираем части спереди, пока накопленное больше перекрытия или новая часть не помещается.
                    while (total > chunkOverlap || (total + length > chunkSize && total > 0)) {
                        total -= current.first().length
                        current.removeFirst()
                    }
                }
            }
            current.addLast(piece)
            total += length
        }
        joinTrimmed(current)?.let(documents::add)
        return documents
    }

    /** Склеивает части и убирает пробелы по краям; пустой результат — `null`. */
    private fun joinTrimmed(parts: Collection<String>): String? = parts.joinToString("").trim().ifEmpty { null }
}
