package shuman.bsu.famcs.rag.knowledge.retrieval

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.knowledge.model.PageType
import kotlin.test.assertEquals

/** Проверка поправок порядка выдачи по типу страницы и году вопроса. */
class RankingPolicyTest {
    private val policy = RankingPolicy(mapOf(PageType.LIST to 0.25, PageType.HISTORY to 0.7, PageType.NEWS to 0.85), yearBoost = 1.5)

    @Test
    fun `множитель берётся по типу страницы, неуказанный тип не меняется`() {
        assertEquals(0.25, policy.factor(PageType.LIST, null, emptySet()))
        assertEquals(0.7, policy.factor(PageType.HISTORY, 1997, emptySet()))
        assertEquals(1.0, policy.factor(PageType.SECTION, null, emptySet()))
    }

    @Test
    fun `год вопроса повышает страницы этого года и снимает понижение хроники`() {
        val years = RankingPolicy.yearsIn("кто был деканом в 1997 году")

        assertEquals(setOf(1997), years)
        assertEquals(1.5, policy.factor(PageType.HISTORY, 1997, years), "хроника нужного года: без понижения, с повышением")
        assertEquals(1.0, policy.factor(PageType.HISTORY, 2001, years), "хроника другого года: без понижения, без повышения")
        assertEquals(1.5, policy.factor(PageType.SECTION, 1997, years))
    }

    @Test
    fun `годы ищутся как четыре цифры в диапазоне, остальные числа не считаются`() {
        assertEquals(setOf(2025), RankingPolicy.yearsIn("приём в 2025 году, телефон 209-50-63, 12 июля"))
        assertEquals(emptySet(), RankingPolicy.yearsIn("кабинет 4512"))
    }

    @Test
    fun `нейтральная политика ничего не меняет`() {
        assertEquals(1.0, RankingPolicy.NEUTRAL.factor(PageType.LIST, 2020, setOf(2020)))
    }

    @Test
    fun `страница-сирота получает свой множитель поверх типа и года`() {
        val withOrphans = RankingPolicy(mapOf(PageType.NEWS to 0.85), yearBoost = 1.5, orphanWeight = 0.8)

        assertEquals(0.8, withOrphans.factor(PageType.SECTION, null, emptySet(), orphan = true), 1e-9)
        assertEquals(0.85 * 0.8, withOrphans.factor(PageType.NEWS, null, emptySet(), orphan = true), 1e-9)
        assertEquals(1.5 * 0.8, withOrphans.factor(PageType.SECTION, 2026, setOf(2026), orphan = true), 1e-9)
        assertEquals(1.0, withOrphans.factor(PageType.SECTION, null, emptySet()), 1e-9)
    }
}
