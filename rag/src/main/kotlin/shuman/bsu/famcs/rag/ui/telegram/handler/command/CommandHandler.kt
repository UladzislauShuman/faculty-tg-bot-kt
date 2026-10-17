package shuman.bsu.famcs.rag.ui.telegram.handler.command

import shuman.bsu.famcs.rag.ui.telegram.model.IncomingMessage

/**
 * Обработчик одной команды бота (`/start`, `/help`…).
 *
 * Чтобы добавить команду, нужен класс с этим интерфейсом и регистрация в `TelegramHandlerFactory`.
 */
interface CommandHandler {
    /**
     * Проверяет, что обработчик отвечает за команду.
     *
     * @param command имя команды строчными буквами, без `/` и без `@имя_бота`.
     */
    fun supports(command: String): Boolean

    /**
     * Выполняет команду.
     *
     * @param message сообщение с командой.
     * @param command имя команды в том же виде, что и в [supports].
     * @param args слова после команды.
     */
    suspend fun handle(
        message: IncomingMessage,
        command: String,
        args: List<String>,
    )
}
