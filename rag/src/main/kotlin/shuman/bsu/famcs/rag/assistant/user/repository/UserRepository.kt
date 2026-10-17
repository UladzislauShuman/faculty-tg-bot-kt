package shuman.bsu.famcs.rag.assistant.user.repository

import shuman.bsu.famcs.rag.api.model.UserRecord
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Instant

/**
 * Хранилище пользователей (таблица `app_user`).
 *
 * Хранится только Telegram ID, версия согласия и время обращений: имя, username и телефон бот не сохраняет.
 */
interface UserRepository {
    /**
     * Создаёт запись пользователя при первом обращении, иначе обновляет время последнего обращения.
     * Заодно снимает отметку «заблокировал бота»: раз пишет, значит, снова доступен.
     *
     * @param userId Telegram ID пользователя.
     * @param at момент обращения.
     * @return сведения о согласии: версия текста и время, либо пустые значения, если согласия ещё нет.
     */
    suspend fun touch(
        userId: TelegramId,
        at: Instant,
    ): UserRecord

    /**
     * Записывает согласие на обработку данных.
     *
     * @param userId Telegram ID пользователя.
     * @param version версия текста согласия, на который дано согласие.
     * @param at момент согласия.
     */
    suspend fun saveConsent(
        userId: TelegramId,
        version: String,
        at: Instant,
    )

    /**
     * Помечает пользователя неактивным: он заблокировал бота, и ответы ему отправлять нельзя.
     *
     * @param userId Telegram ID пользователя.
     */
    suspend fun markInactive(userId: TelegramId)

    /**
     * Удаляет пользователя вместе с сессиями, сообщениями, оценками и счётчиками (каскад в базе).
     * В журнале ответов остаются обезличенные строки для статистики: хеш пользователя и текст запроса стираются.
     *
     * @param userId Telegram ID пользователя.
     */
    suspend fun erase(userId: TelegramId)
}
