package shuman.bsu.famcs.rag.knowledge.chunking.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import shuman.bsu.famcs.rag.knowledge.chunking.Chunker
import shuman.bsu.famcs.rag.knowledge.model.ChildChunk
import shuman.bsu.famcs.rag.knowledge.model.ChunkedDocument
import shuman.bsu.famcs.rag.knowledge.model.FetchedDocument
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
) : Chunker {
    override fun supports(mediaType: String): Boolean = mediaType.startsWith("text/html")

    override fun chunk(document: FetchedDocument): ChunkedDocument {
        val page = Jsoup.parse(document.body, document.url)
        val title = page.title().trim().ifEmpty { NO_TITLE }

        val block = contentSelectors.firstNotNullOfOrNull { selector -> page.selectFirst(selector) }
        if (block == null) {
            log.warn { "Не найден основной блок содержимого: ${document.url}" }
            return ChunkedDocument(document.url, title, parents = emptyList(), children = emptyList())
        }
        removedTags.forEach { tag -> block.select(tag).remove() }

        // Раздел без внутренних заголовков (хроника года) делится на окна: реранкер читает только начало длинного раздела.
        val sections = headerSplitter.split(converter.convert(block)).flatMap { section ->
            ParentWindowSplitter.split(section.text, maxParentChars).map { section.copy(text = it) }
        }
        val parents = sections.mapIndexed { index, section ->
            ParentChunk(
                id = stableId("${document.url}|parent|$index|${section.text}"),
                text = section.text,
                headings = section.headings,
            )
        }
        val children = parents.flatMap { parent ->
            childSplitter.split(parent.text).map { text ->
                ChildChunk(id = stableId("${document.url}\n$text"), parentId = parent.id, text = text)
            }
        }
        val kind = PageTypeResolver.resolve(document.url, page.body().className(), title)
        return ChunkedDocument(document.url, title, parents, children, kind.type, kind.year)
    }

    /** Идентификатор из хеша содержимого: формат UUID подходит для идентификаторов точек Qdrant. */
    private fun stableId(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    private companion object {
        const val NO_TITLE = "No Title"
    }
}
