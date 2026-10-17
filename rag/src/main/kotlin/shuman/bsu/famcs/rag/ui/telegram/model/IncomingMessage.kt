package shuman.bsu.famcs.rag.ui.telegram.model

import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.identifier.UpdateId
import java.time.Instant

/** Входящее сообщение из личного диалога. */
data class IncomingMessage(
    val updateId: UpdateId,
    val userId: TelegramId,
    val chatId: ChatId,
    /** Текст. `null` — пришло не текстовое сообщение (фото, голос, файл). */
    val text: String?,
    val sentAt: Instant,
)
