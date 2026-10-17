package shuman.bsu.famcs.rag.platform.text

/**
 * Подстановка значений в текстовые шаблоны с метками `{имя}`: промпты и сообщения пользователю.
 *
 * Один проход по шаблону: вставленное значение (найденный документ, вопрос пользователя) повторно не просматривается,
 * поэтому `{input}` внутри значения не подменится. Неизвестная метка остаётся как есть, чтобы опечатка была видна в тексте.
 */
object Templates {
    private val PLACEHOLDER = Regex("\\{(\\w+)}")

    /**
     * Подставляет значения в метки `{имя}`. Метка без значения остаётся как есть: пропущенную подстановку видно в тексте.
     *
     * @param template шаблон с метками.
     * @param values значения по именам меток.
     */
    fun fill(
        template: String,
        values: Map<String, String>,
    ): String = PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]] ?: match.value }

    /**
     * То же, что [fill] со значениями-парами: `fill(text, "n" to "5")`.
     *
     * @param template шаблон с метками.
     * @param values пары «имя метки — значение».
     */
    fun fill(
        template: String,
        vararg values: Pair<String, String>,
    ): String = fill(template, values.toMap())
}
