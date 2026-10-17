package shuman.bsu.famcs.rag.common

/**
 * Подстановка значений в текстовые шаблоны с метками `{имя}`: промпты и сообщения пользователю.
 *
 * Один проход по шаблону: вставленное значение (найденный документ, вопрос пользователя) повторно не просматривается,
 * поэтому `{input}` внутри значения не подменится. Неизвестная метка остаётся как есть, чтобы опечатка была видна в тексте.
 */
object Templates {
    private val PLACEHOLDER = Regex("\\{(\\w+)}")

    fun fill(
        template: String,
        values: Map<String, String>,
    ): String = PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]] ?: match.value }

    fun fill(
        template: String,
        vararg values: Pair<String, String>,
    ): String = fill(template, values.toMap())
}
