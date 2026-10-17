package shuman.bsu.famcs.rag.assistant.user.service.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.api.model.UserRecord
import shuman.bsu.famcs.rag.assistant.user.repository.UserRepository
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Реализация [UserService]: порядок работы с пользователем поверх хранилища [UserRepository].
 *
 * Идентификаторы пользователей в журнал не пишутся, фиксируется только факт события.
 *
 * @property repository хранилище пользователей.
 */
class UserServiceImpl(
    private val repository: UserRepository,
) : UserService {
    override suspend fun visit(
        userId: TelegramId,
        at: Instant,
    ): UserRecord = repository.touch(userId, at)

    override suspend fun grantConsent(
        userId: TelegramId,
        version: String,
        at: Instant,
    ) {
        repository.saveConsent(userId, version, at)
        log.info { "Получено согласие на обработку данных, версия текста $version" }
    }

    override suspend fun markBlocked(userId: TelegramId) {
        repository.markInactive(userId)
        log.info { "Пользователь заблокировал бота: ответы ему больше не отправляются" }
    }

    override suspend fun forget(userId: TelegramId) {
        repository.erase(userId)
        log.info { "Данные пользователя удалены по его запросу" }
    }
}
