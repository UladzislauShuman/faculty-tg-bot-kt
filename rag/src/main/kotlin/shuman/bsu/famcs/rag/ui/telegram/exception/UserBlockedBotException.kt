package shuman.bsu.famcs.rag.ui.telegram.exception

/** Пользователь заблокировал бота (Telegram ответил 403). Ответы ему больше не отправляются. */
class UserBlockedBotException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
