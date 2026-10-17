package shuman.bsu.famcs.rag.admin.repository

import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Instant

/** Журнал административных команд (таблица `admin_audit`): кто и что вызвал. */
interface AdminAuditRepository {
    /**
     * Записывает вызов команды.
     *
     * @param adminId Telegram ID администратора.
     * @param command имя команды без префикса (`stats`, `reindex`).
     * @param at момент вызова.
     */
    suspend fun record(
        adminId: TelegramId,
        command: String,
        at: Instant,
    )
}
