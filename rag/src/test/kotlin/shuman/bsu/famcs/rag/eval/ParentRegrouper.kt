package shuman.bsu.famcs.rag.eval

import shuman.bsu.famcs.rag.knowledge.chunking.implementation.ParentWindowSplitter
import shuman.bsu.famcs.rag.knowledge.model.SearchHit

/**
 * Пересобирает родительские разделы найденных фрагментов без переиндексации.
 *
 * Мелкие фрагменты для поиска от размера родителя не зависят, поэтому «что было бы с родителями другого размера» можно
 * проверить на тех же фрагментах: длинный родитель режется на окна, а каждый фрагмент уходит в то окно, где он лежит.
 * Это приближение: настоящая переиндексация может резать фрагменты на границах иначе.
 */
object ParentRegrouper {
    /**
     * Делит длинные родители найденных фрагментов на окна не длиннее [maxChars].
     *
     * @param hits найденные фрагменты.
     * @param maxChars предельная длина родителя в символах; родители короче не меняются.
     * @return фрагменты с новыми идентификаторами и текстами родителей.
     */
    fun regroup(
        hits: List<SearchHit>,
        maxChars: Int,
    ): List<SearchHit> = hits.map { hit ->
        if (hit.parentText.length <= maxChars) return@map hit
        val windows = ParentWindowSplitter.windows(hit.parentText, maxChars)
        val offset = hit.parentText.indexOf(hit.text.take(PROBE)).coerceAtLeast(0)
        val index = windows.indexOfFirst { offset < it.last + 1 }.coerceAtLeast(0)
        hit.copy(parentId = "${hit.parentId}#$index", parentText = hit.parentText.substring(windows[index]))
    }

    /** Сколько первых символов фрагмента используется для поиска его места в родителе. */
    private const val PROBE = 40
}
