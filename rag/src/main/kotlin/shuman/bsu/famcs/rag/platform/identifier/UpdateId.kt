package shuman.bsu.famcs.rag.platform.identifier

/** Номер обновления Telegram (`update_id`). По нему отсеиваются повторные доставки. */
@JvmInline
value class UpdateId(val value: Long)
