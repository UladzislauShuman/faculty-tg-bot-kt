package shuman.bsu.famcs.rag.api

import shuman.bsu.famcs.rag.platform.identifier.TelegramId

/** Административные команды для адаптера: он проверяет права, а выполнение и аудит остаются здесь. */
fun interface AdminService {
    /**
     * Выполняет административную команду и возвращает текст для администратора.
     *
     * @param adminId Telegram ID администратора (права проверяет адаптер).
     * @param command имя команды без префикса `admin_`: `stats`, `feedback`, `gaps`, `reindex`, `maintenance`.
     * @param args аргументы команды.
     * @return готовый текст ответа.
     */
    suspend fun execute(
        adminId: TelegramId,
        command: String,
        args: List<String>,
    ): String
}
