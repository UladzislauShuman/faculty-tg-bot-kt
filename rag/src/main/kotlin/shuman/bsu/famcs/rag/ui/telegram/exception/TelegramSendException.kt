package shuman.bsu.famcs.rag.ui.telegram.exception

/** Отправка не удалась по иной причине (сеть, 5xx, исчерпаны повторы после 429). */
class TelegramSendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
