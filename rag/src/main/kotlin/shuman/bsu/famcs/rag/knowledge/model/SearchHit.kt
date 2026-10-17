package shuman.bsu.famcs.rag.knowledge.model

import java.time.Instant

/** Найденный фрагмент с родителем и сведениями об источнике. */
data class SearchHit(
    val id: String,
    /** Оценка после слияния. Только для сравнения внутри одного ответа, не абсолютная мера. */
    val score: Double,
    val text: String,
    val parentId: String,
    val parentText: String,
    val url: String,
    val title: String,
    val headings: Map<String, String>,
    val indexedAt: Instant?,
)
