package shuman.bsu.famcs.rag.knowledge.chunking.implementation

/**
 * Делит Markdown на разделы по заголовкам и запоминает, под какими заголовками лежит каждый раздел.
 *
 * Порт `MarkdownHeaderTextSplitter` из LangChain (Python), режим `strip_headers=True`, как в baseline: текст заголовка
 * не входит в содержимое раздела, он хранится в метаданных (`H1`, `H2`, `H3`). Абзацы одного раздела склеиваются
 * через «два пробела + перенос строки».
 *
 * Отличие от оригинала (намеренное): неразрывные и узкие пробелы заменяются обычным пробелом. Оригинал удаляет их
 * как непечатаемые символы, из-за чего слова склеиваются («руб. в» → «руб.в»).
 */
class MarkdownHeaderSplitter(
    /** Заголовки, по которым идёт деление: префикс (`#`, `##`) и имя в метаданных (`H1`, `H2`). */
    headersToSplitOn: List<Header>,
) {
    data class Header(val prefix: String, val name: String) {
        val level: Int get() = prefix.count { it == '#' }
    }

    /** Раздел текста с заголовками, под которыми он находится. */
    data class Section(val text: String, val headings: Map<String, String>)

    // Длинные префиксы проверяются первыми: «###» не должен распознаться как «#».
    private val headers = headersToSplitOn.sortedByDescending { it.prefix.length }

    private class Block(val content: String, val headings: Map<String, String>)

    /**
     * Делит Markdown на разделы по заголовкам. Код в ограждениях ``` не разбирается как заголовки.
     * Каждый раздел помнит цепочку заголовков, под которыми он лежит.
     *
     * @param markdown текст в Markdown.
     * @return разделы в порядке следования в документе.
     */
    fun split(markdown: String): List<Section> {
        val blocks = mutableListOf<Block>()
        val currentContent = mutableListOf<String>()
        var currentHeadings: Map<String, String> = emptyMap()
        val headerStack = ArrayDeque<Header>()
        val activeHeadings = linkedMapOf<String, String>()
        var inCodeBlock = false
        var openingFence = ""

        /** Закрывает накопленный блок и начинает новый. */
        fun flush() {
            blocks += Block(currentContent.joinToString("\n"), currentHeadings.toMap())
            currentContent.clear()
        }

        for (rawLine in markdown.split("\n")) {
            val line = printable(rawLine.trim())
            if (!inCodeBlock) {
                if (line.startsWith("```") && line.split("```").size == 2) {
                    inCodeBlock = true
                    openingFence = "```"
                } else if (line.startsWith("~~~")) {
                    inCodeBlock = true
                    openingFence = "~~~"
                }
            } else if (line.startsWith(openingFence)) {
                inCodeBlock = false
                openingFence = ""
            }
            if (inCodeBlock) {
                currentContent += line
                continue
            }

            val header = headerOf(line)
            if (header != null) {
                // Заголовок того же или более высокого уровня закрывает все вложенные.
                while (headerStack.isNotEmpty() && headerStack.last().level >= header.level) {
                    activeHeadings.remove(headerStack.removeLast().name)
                }
                headerStack.addLast(header)
                activeHeadings[header.name] = line.substring(header.prefix.length).trim()
                if (currentContent.isNotEmpty()) flush()
            } else if (line.isNotEmpty()) {
                currentContent += line
            } else if (currentContent.isNotEmpty()) {
                flush()
            }
            currentHeadings = activeHeadings.toMap()
        }
        if (currentContent.isNotEmpty()) {
            blocks += Block(currentContent.joinToString("\n"), currentHeadings)
        }
        return aggregate(blocks)
    }

    /** Заголовок, с которого начинается строка: префикс и затем пробел или конец строки. */
    private fun headerOf(line: String): Header? = headers.firstOrNull { header ->
        line.startsWith(header.prefix) && (line.length == header.prefix.length || line[header.prefix.length] == ' ')
    }

    /** Подряд идущие блоки с одинаковыми заголовками склеиваются в один раздел. */
    private fun aggregate(blocks: List<Block>): List<Section> {
        val result = mutableListOf<Section>()
        for (block in blocks) {
            val last = result.lastOrNull()
            if (last != null && last.headings == block.headings) {
                result[result.lastIndex] = Section(last.text + "  \n" + block.content, last.headings)
            } else {
                result += Section(block.content, block.headings)
            }
        }
        return result
    }

    /** Оставляет печатаемые символы. Пробельные разделители заменяются обычным пробелом. */
    private fun printable(text: String): String = buildString(text.length) {
        text.codePoints().forEach { codePoint ->
            when (Character.getType(codePoint)) {
                Character.SPACE_SEPARATOR.toInt() -> append(' ')

                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.SURROGATE.toInt(),
                Character.PRIVATE_USE.toInt(),
                Character.UNASSIGNED.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> Unit

                else -> appendCodePoint(codePoint)
            }
        }
    }
}
