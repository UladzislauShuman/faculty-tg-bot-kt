package shuman.bsu.famcs.rag.eval.model

/**
 * Ответ бота на один вопрос набора вместе с трассой: сырьё для сквозной проверки.
 *
 * @property id номер вопроса в наборе.
 * @property scenario название сценария; `null` у одиночных вопросов.
 * @property step номер шага в сценарии.
 * @property question вопрос.
 * @property reference эталонный ответ.
 * @property expectedUrl страница из `url` набора.
 * @property status статус ответа бота (`ANSWERED`, `NO_INFO`, `OVERLOADED` и другие).
 * @property answer текст ответа без блока источников.
 * @property route ветка, выбранная классификатором.
 * @property query запрос поиска после переформулирования.
 * @property passageUrls адреса найденных разделов по порядку.
 * @property passageTexts тексты найденных разделов (то, что увидела модель).
 * @property elapsedMs время ответа, мс.
 * @property model модель, давшая ответ.
 * @property candidates кандидаты поиска в порядке реранкера: `адрес|оценка|выбран` (пусто в прогонах до расширения трассы).
 * @property searchMs время поиска, мс.
 * @property rerankMs время реранкера, мс.
 * @property generationMs время генерации, мс.
 */
data class E2eRow(
    val id: Int,
    val scenario: String?,
    val step: Int,
    val question: String,
    val reference: String,
    val expectedUrl: String,
    val status: String,
    val answer: String,
    val route: String?,
    val query: String?,
    val passageUrls: List<String>,
    val passageTexts: List<String>,
    val elapsedMs: Long,
    val model: String?,
    val candidates: List<String> = emptyList(),
    val searchMs: Long = 0,
    val rerankMs: Long = 0,
    val generationMs: Long = 0,
)
