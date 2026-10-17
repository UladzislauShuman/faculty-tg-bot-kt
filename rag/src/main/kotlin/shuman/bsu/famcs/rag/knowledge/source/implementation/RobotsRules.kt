package shuman.bsu.famcs.rag.knowledge.source.implementation

/**
 * Правила `robots.txt` для нашего обходчика.
 *
 * Учитываются группы `User-agent: *` и группа, имя которой входит в наш User-Agent. Правила `Allow` и `Disallow` сравниваются
 * по длине совпавшего префикса: побеждает более длинный, при равной длине разрешение сильнее запрета.
 * Директивы `Crawl-delay` и шаблоны (`*`, `$`) не поддерживаются: для обхода одного сайта факультета этого достаточно.
 */
class RobotsRules private constructor(
    private val allow: List<String>,
    private val disallow: List<String>,
) {
    /**
     * Проверяет путь по правилам: побеждает более длинное совпавшее правило (как в стандарте `robots.txt`).
     *
     * @param path путь без домена.
     * @return `true`, если обходить путь разрешено.
     */
    fun isAllowed(path: String): Boolean {
        val allowLength = allow.filter { path.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        val disallowLength = disallow.filter { path.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        return disallowLength < 0 || allowLength >= disallowLength
    }

    companion object {
        /** Всё разрешено: используется, если `robots.txt` отсутствует или не скачался. */
        val ALLOW_ALL = RobotsRules(emptyList(), emptyList())

        /**
         * Разбирает `robots.txt` и оставляет правила группы, которая относится к нашему `User-Agent` (или к `*`).
         *
         * @param text содержимое файла.
         * @param userAgent название нашего обходчика.
         */
        fun parse(
            text: String,
            userAgent: String,
        ): RobotsRules {
            val allow = mutableListOf<String>()
            val disallow = mutableListOf<String>()
            var groupApplies = false
            var previousWasAgent = false
            for (rawLine in text.lines()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                when (name) {
                    "user-agent" -> {
                        val matches = value == "*" || userAgent.contains(value, ignoreCase = true)
                        // Несколько строк User-agent подряд образуют одну группу: достаточно совпадения любой из них.
                        groupApplies = if (previousWasAgent) groupApplies || matches else matches
                        previousWasAgent = true
                    }

                    "allow" -> {
                        previousWasAgent = false
                        if (groupApplies && value.isNotEmpty()) allow += value
                    }

                    "disallow" -> {
                        previousWasAgent = false
                        if (groupApplies && value.isNotEmpty()) disallow += value
                    }

                    else -> {
                        previousWasAgent = false
                    }
                }
            }
            return RobotsRules(allow, disallow)
        }
    }
}
