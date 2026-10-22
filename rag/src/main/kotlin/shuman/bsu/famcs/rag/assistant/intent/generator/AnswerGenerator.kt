package shuman.bsu.famcs.rag.assistant.intent.generator

import shuman.bsu.famcs.rag.assistant.dialog.model.DialogContext
import shuman.bsu.famcs.rag.assistant.intent.generator.model.GeneratedAnswer
import shuman.bsu.famcs.rag.knowledge.model.RetrievedPassage

/**
 * Генерация ответа по найденным разделам.
 *
 * Отделена от поиска, чтобы проверять шаги по отдельности: генератору можно подать эталонные разделы и увидеть,
 * ошибается ли он при верном контексте. Если ответ плох и при верном контексте, виноват генератор, а не поиск.
 */
fun interface AnswerGenerator {
    /**
     * Формирует ответ только по переданным разделам.
     *
     * @param question вопрос так, как его написал пользователь.
     * @param context история диалога.
     * @param passages разделы базы знаний, на которые можно опираться.
     * @return текст ответа и признак «в разделах ответа нет».
     */
    suspend fun generate(
        question: String,
        context: DialogContext,
        passages: List<RetrievedPassage>,
    ): GeneratedAnswer
}
