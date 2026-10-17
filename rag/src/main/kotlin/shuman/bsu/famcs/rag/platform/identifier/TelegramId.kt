package shuman.bsu.famcs.rag.platform.identifier

/**
 * Идентификатор пользователя Telegram. В логи не попадает: [toString] скрывает значение.
 *
 * Идентификаторы пользователя, чата, обновления, сессии и ответа — отдельные типы: `Long` легко перепутать в сигнатуре,
 * а value-класс не стоит памяти в рантайме, зато компилятор отличает один идентификатор от другого.
 */
@JvmInline
value class TelegramId(val value: Long) {
    override fun toString(): String = "TelegramId(***)"
}
