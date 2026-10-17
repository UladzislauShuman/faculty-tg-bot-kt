package shuman.bsu.famcs.rag.dialog

import shuman.bsu.famcs.rag.common.SessionId
import shuman.bsu.famcs.rag.common.TelegramId
import java.time.Instant

/*
 * Модель данных диалога: сессия, сообщения и то, что попадает в промпт.
 */

/** Кто написал реплику. Совпадает со значениями `message.author` в базе. */
enum class Author { USER, ASSISTANT }

/** Реплика диалога без служебных полей: то, что видит модель. */
data class DialogTurn(
    val author: Author,
    val text: String,
)

/** Сообщение, сохранённое в базе. [id] возрастает со временем, по нему сводка помнит, докуда дошла. */
data class StoredMessage(
    val id: Long,
    val author: Author,
    val text: String,
    val createdAt: Instant,
) {
    fun toTurn() = DialogTurn(author, text)
}

/** Сессия — непрерывный диалог пользователя. Закрывается после паузы или командой `/new`. */
data class ChatSession(
    val id: SessionId,
    val userId: TelegramId,
    /** Краткое содержание старой части диалога. `null`, пока диалог короткий. */
    val summary: String?,
    /** Сообщения с номерами до этого уже учтены в [summary]. */
    val summarizedUntilMessageId: Long,
    val startedAt: Instant,
    val lastActivityAt: Instant,
)

/** Сводка сессии и граница, до которой она дошла. */
data class SessionSummary(
    val text: String?,
    /** Сообщения с номерами до этого уже вошли в [text]. */
    val summarizedUntilMessageId: Long,
)

/** Контекст для обработки вопроса: что помнит бот о текущем диалоге. */
data class DialogContext(
    val sessionId: SessionId,
    val summary: String?,
    /** Последние реплики по порядку, в пределах бюджета токенов. */
    val history: List<DialogTurn>,
) {
    /** Есть ли что-то, на что может ссылаться местоимение в вопросе. */
    val hasMemory: Boolean get() = history.isNotEmpty() || !summary.isNullOrBlank()
}

/** Запись о пользователе. Имя, username и телефон не хранятся вовсе. */
data class UserRecord(
    val consentVersion: String?,
    val consentAt: Instant?,
) {
    /** Согласие дано именно на актуальную версию текста. */
    fun hasConsent(currentVersion: String): Boolean = consentVersion == currentVersion
}
