package shuman.bsu.famcs.rag.api.model

import java.time.Instant

/** Запись о пользователе. Имя, username и телефон не хранятся вовсе. */
data class UserRecord(
    val consentVersion: String?,
    val consentAt: Instant?,
) {
    /** Согласие дано именно на актуальную версию текста. */
    fun hasConsent(currentVersion: String): Boolean = consentVersion == currentVersion
}
