package shuman.bsu.famcs.rag.ui.telegram.model

import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.identifier.UpdateId

/** Нажатие кнопки под сообщением. */
data class IncomingCallback(
    val updateId: UpdateId,
    /** Идентификатор нажатия: на него нужно ответить, иначе у кнопки долго крутится индикатор. */
    val callbackId: String,
    val userId: TelegramId,
    val chatId: ChatId,
    /** Сообщение, под которым нажали кнопку. */
    val messageId: Long,
    /** Данные кнопки (до 64 байт): `consent:accept`, `rate:<ответ>:<оценка>`, `forget:confirm`. */
    val data: String,
)
