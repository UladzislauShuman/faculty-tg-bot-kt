package shuman.bsu.famcs.rag.api.model

import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.identifier.UpdateId
import java.time.Instant

/** Запрос к ядру: кто, откуда, что написал и когда. */
data class RagRequest(
    val userId: TelegramId,
    val chatId: ChatId,
    val updateId: UpdateId,
    /** Текст сообщения. `null` — пришло не текстовое сообщение (фото, голос, файл). */
    val text: String?,
    /** Когда Telegram получил сообщение. Нужно, чтобы отбросить слишком старые сообщения после простоя бота. */
    val sentAt: Instant,
)
