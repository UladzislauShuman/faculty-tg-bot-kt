package shuman.bsu.famcs.rag.platform.text

import kotlin.math.ceil

/**
 * Грубая оценка числа токенов по длине текста.
 *
 * Зачем: бюджеты истории и сводки задаются в токенах, а точный токенизатор есть не у каждого провайдера.
 * Оценка по символам занижает или завышает считаемое на десятки процентов; для бюджета с запасом этого достаточно.
 * Точный расход токенов берётся из ответа провайдера, когда он его сообщает.
 */
class TokenEstimator(
    /** Сколько символов в среднем приходится на токен. Для русского текста около 2.5–3.5. */
    private val charsPerToken: Double,
) {
    init {
        require(charsPerToken > 0.0) { "charsPerToken должен быть положительным" }
    }

    /**
     * Оценивает число токенов в тексте по его длине.
     *
     * @param text текст.
     * @return число токенов, округлённое вверх; для пустого текста 0.
     */
    fun estimate(text: String): Int = if (text.isEmpty()) 0 else ceil(text.length / charsPerToken).toInt()

    /**
     * Оценивает суммарное число токенов в нескольких текстах.
     *
     * @param texts тексты.
     */
    fun estimate(texts: Iterable<String>): Int = texts.sumOf { estimate(it) }
}
