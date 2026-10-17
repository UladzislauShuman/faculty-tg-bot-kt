package shuman.bsu.famcs.rag.knowledge.source

import shuman.bsu.famcs.rag.knowledge.model.FetchResult

/** Скачивает одну страницу по адресу. Вынесено в интерфейс, чтобы обход сайта проверялся без сети. */
interface PageFetcher {
    /**
     * Скачивает страницу.
     *
     * @param url адрес страницы.
     * @return успех с документом, «не найдено», «не поддерживается» (не страница) или «временная ошибка».
     */
    suspend fun fetch(url: String): FetchResult
}
