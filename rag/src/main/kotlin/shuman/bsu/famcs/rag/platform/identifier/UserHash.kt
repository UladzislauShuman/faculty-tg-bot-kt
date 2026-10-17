package shuman.bsu.famcs.rag.platform.identifier

/** Псевдоним пользователя для журнала и метрик: хеш с солью, по нему Telegram ID не восстановить. */
@JvmInline
value class UserHash(val value: String)
