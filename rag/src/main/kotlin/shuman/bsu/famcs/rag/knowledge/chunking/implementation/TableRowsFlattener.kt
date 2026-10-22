package shuman.bsu.famcs.rag.knowledge.chunking.implementation

import org.jsoup.nodes.Element

/**
 * Превращает HTML-таблицы в строки «колонка: значение; колонка: значение».
 *
 * Зачем: в Markdown строка таблицы теряет названия колонок, а мелкий фрагмент в 300 знаков режет таблицу посередине. Вопрос
 * «сколько бюджетных мест на специальности Х» не находил строку, потому что в ней нет слов «бюджет» и «специальность».
 * После преобразования каждая строка самостоятельна и содержит свои заголовки колонок. Таблица без строки заголовков
 * переводится в строки со значениями через « | ».
 */
object TableRowsFlattener {
    /**
     * Заменяет каждую таблицу внутри [root] блоком абзацев по одной строке таблицы на абзац.
     *
     * @param root элемент с содержимым страницы; изменяется на месте.
     * @return сколько таблиц преобразовано.
     */
    fun flatten(root: Element): Int {
        val tables = root.select("table")
        for (table in tables) {
            val rows = table
                .select("tr")
                .map { row ->
                    row.select("th, td").map { it.text().trim() }
                }.filter { cells -> cells.any(String::isNotEmpty) }
            if (rows.isEmpty()) {
                table.remove()
                continue
            }
            val hasHeader = table
                .select("tr")
                .firstOrNull()
                ?.select("th")
                ?.isNotEmpty() == true && rows.size > 1
            val header = if (hasHeader) rows.first() else null
            val body = if (hasHeader) rows.drop(1) else rows
            val holder = Element("div")
            table
                .select("caption")
                .firstOrNull()
                ?.text()
                ?.takeIf(String::isNotBlank)
                ?.let { holder.appendElement("p").text(it) }
            body.forEach { cells -> holder.appendElement("p").text(rowText(header, cells)) }
            table.replaceWith(holder)
        }
        return tables.size
    }

    /** Текст строки: пары «колонка: значение» при известных заголовках, иначе значения через « | ». */
    private fun rowText(
        header: List<String>?,
        cells: List<String>,
    ): String = if (header == null) {
        cells.filter(String::isNotEmpty).joinToString(" | ")
    } else {
        cells
            .mapIndexedNotNull { index, value ->
                value.takeIf(String::isNotEmpty)?.let { v -> header.getOrNull(index)?.takeIf(String::isNotEmpty)?.let { "$it: $v" } ?: v }
            }.joinToString("; ")
    }
}
