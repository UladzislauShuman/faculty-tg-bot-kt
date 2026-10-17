package shuman.bsu.famcs.rag.llm.service

import shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException
import shuman.bsu.famcs.rag.llm.model.LlmReply
import shuman.bsu.famcs.rag.llm.model.LlmRequest

/**
 * Единая точка вызова языковых моделей для всего ядра.
 *
 * Внутри: выбор провайдера по роли, повтор, резерв, ограничитель частоты ошибок, лимит одновременных вызовов.
 * При полном отказе бросает [LlmUnavailableException].
 *
 * Шаги конвейера (переформулирование, классификация, генерация…) знают только этот интерфейс, роль [shuman.bsu.famcs.rag.llm.model.LlmRole]
 * и типы из пакета `model`. Какой провайдер и какая модель стоят за ролью, определяет конфигурация.
 */
interface LlmClient {
    /**
     * Получает ответ модели на запрос. Роль в запросе определяет, какая модель и какая цепочка резервов будет использована.
     *
     * @param request роль и сообщения.
     * @return ответ с указанием, кто его дал и сколько токенов потрачено.
     * @throws shuman.bsu.famcs.rag.llm.exception.LlmUnavailableException если отказали все провайдеры цепочки.
     */
    suspend fun complete(request: LlmRequest): LlmReply
}
