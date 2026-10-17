package shuman.bsu.famcs.rag.assistant.limit.repository

import shuman.bsu.famcs.rag.assistant.limit.model.SystemUsage
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.LocalDate

/**
 * Суточные счётчики: вопросы и токены пользователя и всей системы. По ним работают лимиты и режим экономии.
 * Хранятся в Postgres, поэтому не обнуляются перезапуском бота.
 */
interface UsageRepository {
    /** Сколько вопросов пользователь уже задал в этот день. */
    suspend fun userMessages(
        userId: TelegramId,
        day: LocalDate,
    ): Int

    /** Записывает завершённый вопрос: у пользователя и у системы. [tokens] — расход токенов на этот вопрос. */
    suspend fun recordMessage(
        userId: TelegramId,
        day: LocalDate,
        tokens: Long,
    )

    /** Добавляет расход токенов в счётчик системы (любой вызов модели, в том числе фоновые сводки). Возвращает итог за день. */
    suspend fun addSystemTokens(
        day: LocalDate,
        tokens: Long,
    ): Long

    /**
     * Суммарный расход системы за сутки: по нему работает бюджет токенов.
     *
     * @param day календарный день в часовом поясе бота.
     * @return число вопросов и токенов за день по всем пользователям.
     */
    suspend fun system(day: LocalDate): SystemUsage
}
