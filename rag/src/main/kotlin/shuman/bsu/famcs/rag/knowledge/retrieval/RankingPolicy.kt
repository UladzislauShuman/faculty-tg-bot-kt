package shuman.bsu.famcs.rag.knowledge.retrieval

import shuman.bsu.famcs.rag.knowledge.model.PageType

/**
 * Поправка порядка выдачи после реранкера: множитель оценки по типу страницы и по году, названному в вопросе.
 *
 * Реранкер видит только текст раздела и не знает, что это перечень новостей или хроника. Поправка мягкая (множитель, а не фильтр):
 * по прочитанным исследованиям жёсткая отсечка по дате теряет полноту, а понижение нет. Если в вопросе назван год, хроника этого
 * года не понижается (спрашивают именно о ней), а страницы этого года повышаются.
 *
 * @property typeWeights множители по типу страницы; не указанный тип — 1.
 * @property yearBoost множитель для страниц года из вопроса; 1 — без повышения.
 * @property orphanWeight множитель для страниц-«сирот» (найдены перебором номеров, на них нет ссылок); 1 — без понижения.
 */
class RankingPolicy(
    private val typeWeights: Map<PageType, Double> = emptyMap(),
    private val yearBoost: Double = 1.0,
    private val orphanWeight: Double = 1.0,
) {
    /**
     * Множитель для страницы при данном вопросе.
     *
     * @param type тип страницы.
     * @param pageYear год страницы или `null`.
     * @param queryYears годы, названные в вопросе.
     * @param orphan страница-«сирота».
     */
    fun factor(
        type: PageType,
        pageYear: Int?,
        queryYears: Set<Int>,
        orphan: Boolean = false,
    ): Double {
        val yearMatches = pageYear != null && pageYear in queryYears
        val base = if (type == PageType.HISTORY && queryYears.isNotEmpty()) 1.0 else typeWeights[type] ?: 1.0
        val dated = if (yearMatches) base * yearBoost else base
        return if (orphan) dated * orphanWeight else dated
    }

    companion object {
        /** Без поправок: порядок целиком по реранкеру (как было до введения типов страниц). */
        val NEUTRAL = RankingPolicy()

        private val YEAR = Regex("(?<!\\d)(19[6-9]\\d|20[0-3]\\d)(?!\\d)")

        /** Годы, названные в вопросе (четыре цифры от 1960 до 2039). */
        fun yearsIn(query: String): Set<Int> = YEAR.findAll(query).map { it.value.toInt() }.toSet()
    }
}
