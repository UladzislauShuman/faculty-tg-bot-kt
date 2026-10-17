package shuman.bsu.famcs.rag.ui.telegram.handler.reply

/** Данные кнопок, которые бот прикрепляет к сообщениям и затем узнаёт в нажатиях. */
object CallbackData {
    /** Кнопка «Согласен» под текстом согласия. */
    const val CONSENT_ACCEPT = "consent:accept"

    /** Кнопка подтверждения удаления данных. */
    const val FORGET = "forget:confirm"

    /** Префикс кнопок оценки: далее `<номер ответа>:<оценка>`. */
    const val RATE = "rate:"
}
