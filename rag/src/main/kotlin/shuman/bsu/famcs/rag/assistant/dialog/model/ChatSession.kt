package shuman.bsu.famcs.rag.assistant.dialog.model

import shuman.bsu.famcs.rag.platform.identifier.SessionId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Instant

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
