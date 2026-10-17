package shuman.bsu.famcs.rag.common

/*
 * Типизированные идентификаторы.
 *
 * Зачем: `Long` для пользователя, обновления и ответа легко перепутать в сигнатуре. Value-классы не стоят памяти
 * в рантайме, зато компилятор отличает один идентификатор от другого.
 */

/** Идентификатор пользователя Telegram. В логи не попадает: [toString] скрывает значение. */
@JvmInline
value class TelegramId(val value: Long) {
    override fun toString(): String = "TelegramId(***)"
}

/** Идентификатор чата Telegram. В личных диалогах совпадает с идентификатором пользователя. */
@JvmInline
value class ChatId(val value: Long) {
    override fun toString(): String = "ChatId(***)"
}

/** Номер обновления Telegram (`update_id`). По нему отсеиваются повторные доставки. */
@JvmInline
value class UpdateId(val value: Long)

/** Идентификатор сессии диалога. */
@JvmInline
value class SessionId(val value: Long)

/** Идентификатор записи журнала ответа. К нему привязаны кнопки оценки. */
@JvmInline
value class AnswerId(val value: Long)

/** Псевдоним пользователя для журнала и метрик: хеш с солью, по нему Telegram ID не восстановить. */
@JvmInline
value class UserHash(val value: String)
