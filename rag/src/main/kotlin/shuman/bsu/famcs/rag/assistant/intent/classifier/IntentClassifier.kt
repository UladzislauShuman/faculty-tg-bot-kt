package shuman.bsu.famcs.rag.assistant.intent.classifier

import shuman.bsu.famcs.rag.assistant.intent.model.Intent

/**
 * Выбор ветки обработки по самостоятельной формулировке вопроса.
 *
 * Принцип «сомневаешься — RAG»: ошибка в сторону RAG стоит одного лишнего поиска, а ошибка в другую сторону лишает
 * пользователя ответа по документам.
 */
fun interface IntentClassifier {
    /**
     * Определяет, какой веткой отвечать на запрос.
     *
     * @param query самостоятельный запрос после переформулирования.
     * @return выбранная ветка.
     */
    suspend fun classify(query: String): Intent
}
