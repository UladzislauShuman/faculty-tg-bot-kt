package shuman.bsu.famcs.rag.knowledge.source

import shuman.bsu.famcs.rag.knowledge.FetchedDocument

/** Результат скачивания одной страницы. */
sealed interface FetchResult {
    data class Success(val document: FetchedDocument) : FetchResult

    /** Страницы больше нет (HTTP 404 или 410). Её фрагменты нужно удалить из индекса. */
    data class NotFound(val url: String, val status: Int) : FetchResult

    /** Адрес отдаёт не страницу (картинка, файл, архив): в индекс не идёт. */
    data class Unsupported(val url: String, val mediaType: String) : FetchResult

    /** Временный сбой (сеть, 5xx, таймаут) после всех повторов. Прежняя версия страницы остаётся в индексе. */
    data class Failed(val url: String, val reason: String) : FetchResult
}

/** Скачивает одну страницу по адресу. Вынесено в интерфейс, чтобы обход сайта проверялся без сети. */
interface PageFetcher {
    suspend fun fetch(url: String): FetchResult
}
