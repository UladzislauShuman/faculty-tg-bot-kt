package shuman.bsu.famcs.rag.api

import shuman.bsu.famcs.rag.platform.identifier.TelegramId

/** Управление диалогом пользователя из адаптера: пока одно действие — начать разговор заново. */
fun interface ConversationService {
    /**
     * Начинает новый диалог (`/new`): текущая сессия закрывается, бот забывает прежний контекст.
     *
     * @param userId Telegram ID пользователя.
     */
    suspend fun reset(userId: TelegramId)
}
