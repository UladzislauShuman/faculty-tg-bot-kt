package shuman.bsu.famcs.rag.platform.text

/**
 * Тексты промптов из ресурсов: файлы `prompts/<профиль>/<имя>.txt`.
 *
 * Зачем в файлах, а не в коде: промпт — это текст, который автор правит и сравнивает по результатам тестов. В файле он читается
 * целиком, без экранирования, и его изменение не требует правки кода. Подстановки записываются как `{имя}`.
 *
 * Профили: `base` содержит все промпты (перенесены из Python-версии без изменений, плюс классификатор и судья). Другой профиль —
 * папка только с изменёнными файлами: чего в ней нет, берётся из `base`. Так новая версия одного промпта сравнивается с базовой
 * на том же наборе вопросов, а остальные промпты остаются одинаковыми.
 *
 * @property profile профиль промптов.
 * @property loader чтение файла по пути в ресурсах; `null`, если файла нет. Подменяется в тестах.
 */
class PromptLibrary(
    val profile: String = BASE,
    private val loader: (String) -> String? = ::loadFromClasspath,
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Возвращает текст промпта и подставляет значения вместо `{имя}`. */
    fun render(
        name: String,
        vararg values: Pair<String, String>,
    ): String {
        val template = cache.getOrPut(name) { load(name) }
        return Templates.fill(template, values.toMap()).trimEnd()
    }

    /** Из какого профиля берётся промпт: из выбранного, если там есть свой файл, иначе из `base`. */
    fun sourceOf(name: String): String = if (profile != BASE && loader(path(profile, name)) != null) profile else BASE

    /** Текст промпта: файл профиля, а если его нет — файл `base`. */
    private fun load(name: String): String =
        (if (profile != BASE) loader(path(profile, name)) else null)
            ?: requireNotNull(loader(path(BASE, name))) { "Нет промпта prompts/$BASE/$name.txt" }

    companion object {
        /** Базовый профиль: полный набор промптов. */
        const val BASE = "base"

        private fun path(
            profile: String,
            name: String,
        ) = "/prompts/$profile/$name.txt"

        /**
         * Читает промпт из ресурсов.
         *
         * @param path путь в ресурсах.
         * @return текст или `null`, если файла нет.
         */
        private fun loadFromClasspath(path: String): String? =
            PromptLibrary::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
