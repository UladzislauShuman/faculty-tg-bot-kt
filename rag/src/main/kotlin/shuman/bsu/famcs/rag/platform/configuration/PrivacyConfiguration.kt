package shuman.bsu.famcs.rag.platform.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import shuman.bsu.famcs.rag.platform.configuration.properties.PrivacyProperties
import shuman.bsu.famcs.rag.platform.privacy.UserHasher

/** Сборка защиты персональных данных: псевдонимизация пользователей для журнала и счётчиков. */
@Configuration(proxyBeanMethods = false)
class PrivacyConfiguration {
    /** Хеш с секретной солью: по нему Telegram ID пользователя из журнала не восстановить. */
    @Bean
    fun userHasher(properties: PrivacyProperties) = UserHasher(secret = properties.hashSecret, length = properties.hashLength)
}
