package shuman.bsu.famcs.rag.common

/**
 * Тексты промптов из ресурсов: файлы `prompts/<имя>.txt`.
 *
 * Зачем в файлах, а не в коде: промпт — это текст, который автор правит и сравнивает по результатам тестов. В файле он читается
 * целиком, без экранирования, и его изменение не требует правки кода. Подстановки записываются как `{имя}`.
 * Промпты Python-версии перенесены без изменений (паритет), новые — классификатор на четыре метки и режим общих вопросов.
 */
class PromptLibrary(
    private val loader: (String) -> String = ::loadFromClasspath,
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Возвращает текст промпта и подставляет значения вместо `{имя}`. */
    fun render(
        name: String,
        vararg values: Pair<String, String>,
    ): String {
        val template = cache.getOrPut(name) { loader(name) }
        return Templates.fill(template, values.toMap()).trimEnd()
    }

    private companion object {
        fun loadFromClasspath(name: String): String =
            requireNotNull(PromptLibrary::class.java.getResourceAsStream("/prompts/$name.txt")) { "Нет промпта prompts/$name.txt" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
