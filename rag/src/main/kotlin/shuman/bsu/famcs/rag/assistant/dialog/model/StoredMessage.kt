package shuman.bsu.famcs.rag.assistant.dialog.model

import java.time.Instant

/** Сообщение, сохранённое в базе. [id] возрастает со временем, по нему сводка помнит, докуда дошла. */
data class StoredMessage(
    val id: Long,
    val author: Author,
    val text: String,
    val createdAt: Instant,
) {
    /** Превращает сохранённое сообщение в реплику для промпта: автор и текст без служебных полей. */
    fun toTurn() = DialogTurn(author, text)
}
