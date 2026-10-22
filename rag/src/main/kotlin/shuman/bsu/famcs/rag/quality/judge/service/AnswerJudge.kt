package shuman.bsu.famcs.rag.quality.judge.service

import shuman.bsu.famcs.rag.quality.judge.model.JudgeCase
import shuman.bsu.famcs.rag.quality.judge.model.JudgeCriterion
import shuman.bsu.famcs.rag.quality.judge.model.JudgeResult

/**
 * Судья ответов: проверяет один ответ по одному критерию и возвращает вердикт «верно / неверно / не знаю».
 *
 * За интерфейсом, чтобы заменить судью (другая модель, правила без модели, ручная разметка) без правки проверок.
 * Доверять вердикту можно только после сверки с ручной разметкой ([AgreementCalculator]).
 */
fun interface AnswerJudge {
    /**
     * Оценивает ответ по критерию.
     *
     * @param criterion что проверять.
     * @param case вопрос, ответ и данные для проверки (эталон, разделы).
     * @return вердикт и причина. При сбое модели вердикт `UNKNOWN`.
     */
    suspend fun judge(
        criterion: JudgeCriterion,
        case: JudgeCase,
    ): JudgeResult
}
