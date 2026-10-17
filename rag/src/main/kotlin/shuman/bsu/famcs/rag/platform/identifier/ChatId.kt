package shuman.bsu.famcs.rag.platform.identifier

/** Идентификатор чата Telegram. В личных диалогах совпадает с идентификатором пользователя. */
@JvmInline
value class ChatId(val value: Long) {
    override fun toString(): String = "ChatId(***)"
}
