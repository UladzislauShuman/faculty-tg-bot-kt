package shuman.bsu.famcs.rag.assistant.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.api.UserService
import shuman.bsu.famcs.rag.assistant.user.repository.UserRepository
import shuman.bsu.famcs.rag.assistant.user.repository.jdbc.UserRepositoryJdbcImpl
import shuman.bsu.famcs.rag.assistant.user.service.implementation.UserServiceImpl
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.privacy.UserHasher

/** Сборка модуля пользователей: хранилище и сервис согласия и удаления данных. */
@Configuration(proxyBeanMethods = false)
class UserConfiguration {
    /** Хранилище пользователей в PostgreSQL. */
    @Bean
    fun userRepository(
        database: Database,
        hasher: UserHasher,
    ): UserRepository = UserRepositoryJdbcImpl(database, hasher)

    /** Сервис пользователей, который вызывают адаптеры и предпроверки. */
    @Bean
    fun userService(repository: UserRepository): UserService = UserServiceImpl(repository)
}
