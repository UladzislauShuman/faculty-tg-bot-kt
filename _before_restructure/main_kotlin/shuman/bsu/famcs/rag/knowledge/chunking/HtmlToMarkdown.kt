package shuman.bsu.famcs.rag.knowledge.chunking

import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Преобразует HTML в Markdown. Порт правил библиотеки `markdownify` 0.14 (Python), которая стояла в baseline.
 *
 * Зачем свой порт, а не готовая JVM-библиотека: форма текста определяет, что попадёт в чанки и эмбеддинги.
 * Те же правила дают сравнимый индекс и упрощают проверку паритета: заголовки в стиле ATX, списки с маркерами `*`, `+`, `-`
 * по уровням вложенности, ссылки `[текст](адрес)`, жирный `**`, перенос строки `два пробела + \n`.
 *
 * Отличия от оригинала (намеренные): ссылки при желании приводятся к абсолютным, чтобы бот мог сослаться на документ.
 */
class HtmlToMarkdown(
    /** Экранировать `*` в тексте. В baseline включено. */
    private val escapeAsterisks: Boolean,
    /** Экранировать `_` в тексте. В baseline включено. */
    private val escapeUnderscores: Boolean,
    /** Маркеры списков по уровню вложенности: первый уровень первым символом, второй вторым и так далее по кругу. */
    private val bullets: String,
    /** Превращать относительные ссылки в абсолютные (нужен базовый адрес документа). */
    private val absolutizeLinks: Boolean,
) {
    /** Преобразует элемент вместе с его содержимым. */
    fun convert(root: Element): String = processTag(root, convertAsInline = false)

    private fun processTag(
        node: Element,
        convertAsInline: Boolean,
    ): String {
        val name = node.normalName()
        val isHeading = HEADING_NAME.matches(name)
        val isCell = name == "td" || name == "th"
        val childrenAsInline = convertAsInline || isHeading || isCell

        removeIgnorableWhitespace(node)

        var text = ""
        for (child in node.childNodes().toList()) {
            when (child) {
                is Comment, is DocumentType, is DataNode -> {
                    Unit
                }

                is TextNode -> {
                    text += processText(child)
                }

                is Element -> {
                    val stripped = text.trimEnd('\n')
                    val newlinesLeft = text.length - stripped.length
                    val nextText = processTag(child, childrenAsInline)
                    val nextStripped = nextText.trimStart('\n')
                    val newlinesRight = nextText.length - nextStripped.length
                    text = stripped + "\n".repeat(maxOf(newlinesLeft, newlinesRight)) + nextStripped
                }

                else -> {
                    Unit
                }
            }
        }
        return convertElement(node, text, convertAsInline)
    }

    /** Удаляет пробельные текстовые узлы в начале и конце блочных элементов и рядом с ними. */
    private fun removeIgnorableWhitespace(node: Element) {
        val removeInside = shouldRemoveWhitespaceInside(node)
        for (child in node.childNodes().toList()) {
            // Кандидаты на удаление — только текстовые узлы из одних пробелов.
            if (child !is TextNode || !child.wholeText.isBlank()) continue
            val previous = child.previousSibling()
            val next = child.nextSibling()
            val canExtract = (removeInside && (previous == null || next == null)) ||
                shouldRemoveWhitespaceOutside(previous) ||
                shouldRemoveWhitespaceOutside(next)
            if (canExtract) child.remove()
        }
    }

    private fun processText(node: TextNode): String {
        var text = node.wholeText
        val insidePre = hasAncestor(node, "pre")
        if (!insidePre) {
            text = NEWLINE_WHITESPACE.replace(text, "\n")
            text = SPACES_AND_TABS.replace(text, " ")
        }
        if (!hasAncestor(node, "pre", "code", "kbd", "samp")) {
            text = escape(text)
        }
        val previous = node.previousSibling()
        val next = node.nextSibling()
        val parent = node.parent()
        if (shouldRemoveWhitespaceOutside(previous) || (shouldRemoveWhitespaceInside(parent) && previous == null)) {
            text = text.trimStart()
        }
        if (shouldRemoveWhitespaceOutside(next) || (shouldRemoveWhitespaceInside(parent) && next == null)) {
            text = text.trimEnd()
        }
        return text
    }

    private fun escape(text: String): String {
        var result = text
        if (escapeAsterisks) result = result.replace("*", "\\*")
        if (escapeUnderscores) result = result.replace("_", "\\_")
        return result
    }

    private fun convertElement(
        el: Element,
        text: String,
        inline: Boolean,
    ): String = when (val name = el.normalName()) {
        "a" -> convertLink(el, text)

        "b", "strong" -> convertInline(el, text, "**")

        "em", "i" -> convertInline(el, text, "*")

        "del", "s" -> convertInline(el, text, "~~")

        "sub", "sup" -> convertInline(el, text, "")

        "code", "kbd", "samp" -> if (el.parent()?.normalName() == "pre") text else convertInline(el, text, "`")

        "br" -> if (inline) "" else "  \n"

        "blockquote" -> convertBlockquote(text, inline)

        "hr" -> "\n\n---\n\n"

        "img" -> convertImage(el, inline)

        "ul", "ol" -> convertList(el, text)

        "li" -> convertListItem(el, text)

        "p" -> if (inline) {
            " ${text.trim()} "
        } else if (text.isNotEmpty()) {
            "\n\n$text\n\n"
        } else {
            ""
        }

        "pre" -> if (text.isEmpty()) "" else "\n```\n$text\n```\n"

        "script", "style" -> ""

        "table" -> "\n\n$text\n"

        "caption" -> "$text\n"

        "figcaption" -> "\n\n$text\n\n"

        "td", "th" -> convertCell(el, text)

        "tr" -> convertRow(el, text)

        else -> if (HEADING_NAME.matches(name)) convertHeading(name.substring(1).toInt(), text, inline) else text
    }

    /** Простые встроенные теги. Пробелы по краям выносятся наружу разметки: `<b> a</b>` → ` **a**`. */
    private fun convertInline(
        el: Element,
        text: String,
        marker: String,
    ): String {
        if (hasAncestor(el, "pre", "code", "kbd", "samp")) return text
        val prefix = if (text.startsWith(' ')) " " else ""
        val suffix = if (text.endsWith(' ')) " " else ""
        val core = text.trim()
        if (core.isEmpty()) return ""
        return "$prefix$marker$core$marker$suffix"
    }

    private fun convertLink(
        el: Element,
        text: String,
    ): String {
        val prefix = if (text.startsWith(' ')) " " else ""
        val suffix = if (text.endsWith(' ')) " " else ""
        val core = text.trim()
        if (core.isEmpty()) return ""
        val rawHref = el.attr("href")
        if (rawHref.isEmpty()) return core
        val href = if (absolutizeLinks) el.absUrl("href").ifEmpty { rawHref } else rawHref
        val title = el.attr("title")
        if (core.replace("\\_", "_") == rawHref && title.isEmpty()) return "<$href>"
        val titlePart = if (title.isNotEmpty()) " \"${title.replace("\"", "\\\"")}\"" else ""
        return "$prefix[$core]($href$titlePart)$suffix"
    }

    private fun convertBlockquote(
        text: String,
        inline: Boolean,
    ): String = when {
        inline -> " ${text.trim()} "
        text.isEmpty() -> ""
        else -> "\n" + text.trim().lines().joinToString("\n") { "> $it" } + "\n\n"
    }

    private fun convertImage(
        el: Element,
        inline: Boolean,
    ): String {
        val alt = el.attr("alt")
        if (inline) return alt
        val src = el.attr("src")
        val title = el.attr("title")
        val titlePart = if (title.isNotEmpty()) " \"${title.replace("\"", "\\\"")}\"" else ""
        return "![$alt]($src$titlePart)"
    }

    private fun convertHeading(
        level: Int,
        text: String,
        inline: Boolean,
    ): String {
        if (inline) return text
        val clamped = level.coerceIn(1, 6)
        val title = ALL_WHITESPACE.replace(text.trim(), " ")
        return "\n${"#".repeat(clamped)} $title\n\n"
    }

    private fun convertList(
        el: Element,
        text: String,
    ): String {
        val next = el.nextSibling()
        val beforeParagraph = next != null && !(next is Element && (next.normalName() == "ul" || next.normalName() == "ol"))
        val nested = generateSequence(el as Element?) { it.parent() }.any { it.normalName() == "li" }
        return if (nested) "\n" + text.trimEnd() else "\n\n" + text + (if (beforeParagraph) "\n" else "")
    }

    private fun convertListItem(
        el: Element,
        text: String,
    ): String {
        val parent = el.parent()
        val bullet = if (parent?.normalName() == "ol") {
            val start = parent.attr("start").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt() ?: 1
            "${start + el.siblingIndex()}."
        } else {
            val depth = generateSequence(el as Element?) { it.parent() }.count { it.normalName() == "ul" } - 1
            bullets[depth.coerceAtLeast(0) % bullets.length].toString()
        } + " "
        val body = text.trim()
        if (body.isEmpty()) return "\n"
        // Продолжение пункта сдвигается на ширину маркера, чтобы вложенное содержимое осталось внутри пункта.
        val indented = body.lines().mapIndexed { index, line -> if (index == 0) line else " ".repeat(bullet.length) + line }
        return bullet + indented.joinToString("\n") + "\n"
    }

    private fun convertCell(
        el: Element,
        text: String,
    ): String {
        val colspan = el.attr("colspan").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt() ?: 1
        return " " + text.trim().replace("\n", " ") + " |".repeat(colspan)
    }

    private fun convertRow(
        el: Element,
        text: String,
    ): String {
        val cells = el.select("td, th")
        val previous = el.previousSibling()
        val parentName = el.parent()?.normalName()
        val isHeadRow = cells.all { it.normalName() == "th" } ||
            (previous == null && parentName != "tbody") ||
            (
                previous == null && parentName == "tbody" && el
                    .parent()
                    ?.parent()
                    ?.select("thead")
                    ?.isEmpty() == true
            )
        var overline = ""
        var underline = ""
        if (isHeadRow && previous == null) {
            val fullColspan = cells.sumOf { cell ->
                cell.attr("colspan").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt() ?: 1
            }
            underline += "| " + List(fullColspan) { "---" }.joinToString(" | ") + " |\n"
        } else if (previous == null && (parentName == "table" || (parentName == "tbody" && el.parent()?.previousSibling() == null))) {
            overline += "| " + List(cells.size) { "" }.joinToString(" | ") + " |\n"
            overline += "| " + List(cells.size) { "---" }.joinToString(" | ") + " |\n"
        }
        return "$overline|$text\n$underline"
    }

    private fun shouldRemoveWhitespaceInside(node: Node?): Boolean {
        val name = (node as? Element)?.normalName() ?: return false
        return HEADING_NAME.matches(name) || name in BLOCK_WITH_STRIPPED_WHITESPACE
    }

    private fun shouldRemoveWhitespaceOutside(node: Node?): Boolean =
        shouldRemoveWhitespaceInside(node) || (node as? Element)?.normalName() == "pre"

    private fun hasAncestor(
        node: Node,
        vararg names: String,
    ): Boolean = generateSequence(node.parent()) { it.parent() }.any { it is Element && it.normalName() in names }

    private companion object {
        val HEADING_NAME = Regex("h[1-6]")
        val NEWLINE_WHITESPACE = Regex("[\\t \\r\\n]*[\\r\\n][\\t \\r\\n]*")
        val SPACES_AND_TABS = Regex("[\\t ]+")
        val ALL_WHITESPACE = Regex("[\\t \\r\\n]+")
        val BLOCK_WITH_STRIPPED_WHITESPACE =
            setOf("p", "blockquote", "ol", "ul", "li", "table", "thead", "tbody", "tfoot", "tr", "td", "th")
    }
}
