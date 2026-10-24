package shuman.bsu.famcs.rag.assistant.pipeline.service

import shuman.bsu.famcs.rag.assistant.configuration.properties.ReplyProperties
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import java.util.Locale

/**
 * Строка под ответом: сколько токенов и денег ушло на вопрос. Нужна, чтобы пользователь и автор видели «вес» запроса.
 * Цена считается по тарифу из настроек (`bot.reply.cost`): тариф относится к одной модели, поэтому при смене модели его меняют вместе с ней.
 *
 * @property settings настройки строки стоимости.
 */
class CostFooter(
    private val settings: ReplyProperties.Cost,
) {
    /**
     * Строка стоимости для вопроса.
     *
     * @param usage токены всего вопроса (классификатор, переформулировка, ответ).
     * @return строка или `null`, если показ выключен или модели не вызывались.
     */
    fun render(usage: TokenUsage): String? {
        if (!settings.enabled || usage.totalTokens == 0) return null
        val price = (usage.inputTokens * settings.inputPerMillion + usage.outputTokens * settings.outputPerMillion) / MILLION
        return settings.template
            .replace("{cost}", formatCost(price))
            .replace("{currency}", settings.currency)
            .replace("{input}", usage.inputTokens.toString())
            .replace("{output}", usage.outputTokens.toString())
    }

    /** Цена в виде «0,0004»: четыре значащих знака после запятой хватает для долей цента. */
    private fun formatCost(value: Double): String = String.format(Locale.forLanguageTag("ru"), "%.4f", value)

    private companion object {
        const val MILLION = 1_000_000.0
    }
}
