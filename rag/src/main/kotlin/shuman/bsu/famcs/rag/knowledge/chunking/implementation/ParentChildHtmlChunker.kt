package shuman.bsu.famcs.rag.knowledge.chunking.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import shuman.bsu.famcs.rag.knowledge.chunking.Chunker
import shuman.bsu.famcs.rag.knowledge.model.ChildChunk
import shuman.bsu.famcs.rag.knowledge.model.ChunkedDocument
import shuman.bsu.famcs.rag.knowledge.model.FetchedDocument
import shuman.bsu.famcs.rag.knowledge.model.PageType
import shuman.bsu.famcs.rag.knowledge.model.ParentChunk
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * Разбиватель HTML-страниц сайта на «родителей» и «детей».
 *
 * Шаги: найти блок с основным содержимым → убрать служебные теги → перевести в Markdown → поделить на разделы по
 * заголовкам (родители) → каждый раздел нарезать на мелкие фрагменты с перекрытием (дети).
 * Повторяет `ParentChildHTMLChunker` из Python-версии; идентификаторы чанков детерминированы (одинаковый текст
 * на той же странице даёт тот же идентификатор), поэтому повторная индексация не плодит дубли.
 */
class ParentChildHtmlChunker(
    private val converter: HtmlToMarkdown,
    private val headerSplitter: MarkdownHeaderSplitter,
    private val childSplitter: RecursiveCharacterSplitter,
    /** CSS-селекторы основного блока по порядку приоритета. Берётся первый найденный. */
    private val contentSelectors: List<String>,
    /** Теги, которые вырезаются из блока до преобразования: скрипты, формы, навигация, шапка, подвал. */
    private val removedTags: List<String>,
    /** Предельный размер родителя в символах; длиннее делится на окна. 0 — без ограничения. */
    private val maxParentChars: Int = 0,
    /** Превращать таблицы в строки «колонка: значение» перед преобразованием в Markdown. */
    private val tablesAsRows: Boolean = false,
    /** Страница, весь текст которой не длиннее этого, становится одним родителем. 0 — отключено. */
    private val wholePageMaxChars: Int = 0,
) : Chunker {
    override fun supports(mediaType: String): Boolean = mediaType.startsWith("text/html")

    override fun chunk(document: FetchedDocument): ChunkedDocument {
        val page = Jsoup.parse(document.body, document.url)
        val title = page.title().trim().ifEmpty { NO_TITLE }

        val block = contentSelectors.firstNotNullOfOrNull { selector -> page.selectFirst(selector) }
        if (block == null) {
            log.warn { "Не найден основной блок содержимого: ${document.url}" }
            return ChunkedDocument(document.url, title, parents = emptyList(), children = emptyList(), orphan = document.orphan)
        }
        removedTags.forEach { tag -> block.select(tag).remove() }

        val kind = PageTypeResolver.resolve(document.url, page.body().className(), title)
        // Дата нужна новостям и мероприятиям: у них порядок во времени значим. Берётся до преобразования, пока в блоке есть картинки.
        val published = if (kind.type == PageType.NEWS || kind.type == PageType.EVENT) DateExtractor.find(block, block.text()) else null
        if (tablesAsRows) TableRowsFlattener.flatten(block)

        val rawSections = headerSplitter.split(converter.convert(block))
        val wholePage = wholePageMaxChars > 0 && rawSections.size > 1 && rawSections.sumOf { it.text.length } <= wholePageMaxChars
        val (parents, children) = if (wholePage) wholePageChunks(document.url, rawSections) else sectionChunks(document.url, rawSections)
        return ChunkedDocument(document.url, title, parents, children, kind.type, kind.year, published, document.orphan)
    }

    /**
     * Обычная нарезка: каждый раздел страницы (при необходимости окнами не длиннее предела) — родитель, из него нарезаются дети.
     * Раздел без внутренних заголовков (хроника года) делится на окна: реранкер читает только начало длинного раздела.
     */
    private fun sectionChunks(
        url: String,
        rawSections: List<MarkdownHeaderSplitter.Section>,
    ): Pair<List<ParentChunk>, List<ChildChunk>> {
        val sections = rawSections.flatMap { section ->
            ParentWindowSplitter.split(section.text, maxParentChars).map { section.copy(text = it) }
        }
        val parents = sections.mapIndexed { index, section ->
            ParentChunk(id = stableId("$url|parent|$index|${section.text}"), text = section.text, headings = section.headings)
        }
        val children = parents.flatMap { parent ->
            childSplitter.split(parent.text).map { text -> ChildChunk(id = stableId("$url\n$text"), parentId = parent.id, text = text) }
        }
        return parents to children
    }

    /**
     * Маленькая страница целиком: один родитель из всех разделов с их заголовками, дети нарезаются по разделам и помнят свой
     * заголовок. Любой найденный фрагмент отдаёт в промпт всю страницу: на общий вопрос («как поступить») она отвечает полнее.
     */
    private fun wholePageChunks(
        url: String,
        rawSections: List<MarkdownHeaderSplitter.Section>,
    ): Pair<List<ParentChunk>, List<ChildChunk>> {
        val text = rawSections.joinToString("\n\n") { section ->
            val heading = section.headings.values
                .lastOrNull()
                ?.takeIf(String::isNotBlank)
            if (heading == null) section.text else "## $heading\n${section.text}"
        }
        val parent = ParentChunk(id = stableId("$url|parent|0|$text"), text = text, headings = emptyMap())
        val children = rawSections.flatMap { section ->
            childSplitter.split(section.text).map { piece ->
                ChildChunk(id = stableId("$url\n$piece"), parentId = parent.id, text = piece, headings = section.headings)
            }
        }
        return listOf(parent) to children
    }

    /** Идентификатор из хеша содержимого: формат UUID подходит для идентификаторов точек Qdrant. */
    private fun stableId(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    private companion object {
        const val NO_TITLE = "No Title"
    }
}
