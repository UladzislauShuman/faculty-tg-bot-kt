package shuman.bsu.famcs.rag.api

import shuman.bsu.famcs.rag.api.model.UserRecord
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Instant

/**
 * Реестр пользователей: согласие на обработку данных и право на удаление.
 *
 * Хранится только Telegram ID, версия согласия и время обращений: имя, username и телефон бот не сохраняет.
 */
interface UserService {
    /**
     * Фиксирует обращение: создаёт запись при первом сообщении, иначе обновляет время.
     * Заодно снимает отметку «заблокировал бота»: раз пишет, значит, снова доступен.
     */
    suspend fun visit(
        userId: TelegramId,
        at: Instant,
    ): UserRecord

    /** Записывает согласие вместе с версией текста, на который оно дано. */
    suspend fun grantConsent(
        userId: TelegramId,
        version: String,
        at: Instant,
    )

    /** Пользователь заблокировал бота: ответы ему больше не отправляются, пока он не напишет снова. */
    suspend fun markBlocked(userId: TelegramId)

    /**
     * Полное удаление данных пользователя (`/forget`): сессии, сообщения, оценки, счётчики.
     * В журнале ответов остаются обезличенные строки для статистики: хеш пользователя и текст запроса стираются.
     */
    suspend fun forget(userId: TelegramId)
}
