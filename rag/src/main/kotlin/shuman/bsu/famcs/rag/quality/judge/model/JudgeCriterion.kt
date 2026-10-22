package shuman.bsu.famcs.rag.quality.judge.model

/**
 * Критерий оценки ответа. За один вызов судья проверяет один критерий: так вердикт проще сверить с человеком.
 *
 * @property promptName имя файла промпта в `prompts/`.
 */
enum class JudgeCriterion(
    val promptName: String,
) {
    /** Верность: каждое утверждение ответа следует из найденных разделов (нет выдумки). Нужны разделы и ответ. */
    FAITHFULNESS("judge-faithfulness"),

    /** Корректность: ответ по смыслу совпадает с эталонным. Нужны эталон и ответ. */
    CORRECTNESS("judge-correctness"),

    /** Релевантность: ответ относится к заданному вопросу. Нужны вопрос и ответ. */
    RELEVANCE("judge-relevance"),
}
