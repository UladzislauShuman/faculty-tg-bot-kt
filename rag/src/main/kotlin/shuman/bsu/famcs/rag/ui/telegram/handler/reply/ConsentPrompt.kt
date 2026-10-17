package shuman.bsu.famcs.rag.ui.telegram.handler.reply

import shuman.bsu.famcs.rag.platform.configuration.properties.ConsentProperties
import shuman.bsu.famcs.rag.platform.identifier.ChatId
import shuman.bsu.famcs.rag.ui.telegram.model.Button

/**
 * Запрос согласия на обработку данных: текст и кнопка «Согласен».
 *
 * @property sender отправка сообщений.
 * @property consent текст согласия, подпись кнопки и версия.
 */
class ConsentPrompt(
    private val sender: ReplySender,
    private val consent: ConsentProperties,
) {
    /**
     * Показывает текст согласия с кнопкой.
     *
     * @param chatId чат, куда отправляется запрос.
     */
    suspend fun show(chatId: ChatId) {
        sender.send(chatId, consent.text, listOf(Button(consent.acceptButton, CallbackData.CONSENT_ACCEPT)))
    }
}
