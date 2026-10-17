package shuman.bsu.famcs.rag.assistant.feedback.model

import shuman.bsu.famcs.rag.api.model.AnswerStatus
import shuman.bsu.famcs.rag.assistant.intent.model.Intent
import shuman.bsu.famcs.rag.platform.identifier.UserHash
import java.time.Instant

/**
 * Запись журнала ответов: что и как ответил бот, без Telegram ID. Нужна для оценки качества и поиска пробелов в знаниях.
 * Пользователь связан с записью только хешем с секретом; при `/forget` хеш и текст запроса стираются.
 */
data class AnswerLogEntry(
    /** Сообщение-ответ в диалоге. `null`, если ответа пользователю не было (сбой, перегрузка). */
    val messageId: Long?,
    val userHash: UserHash,
    /** Запрос для поиска (после переформулирования). Хранится ограниченное время (см. `bot.retention`). */
    val query: String?,
    /** Выбранная ветка. `null`, если вопрос не дошёл до классификатора. */
    val route: Intent?,
    val status: AnswerStatus,
    val noInfo: Boolean,
    /** Адреса источников, на которые опирался ответ. */
    val sources: List<String>,
    val provider: String?,
    val model: String?,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Int,
    val createdAt: Instant,
)
