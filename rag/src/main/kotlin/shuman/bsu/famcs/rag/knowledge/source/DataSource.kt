package shuman.bsu.famcs.rag.knowledge.source

import shuman.bsu.famcs.rag.knowledge.model.FetchResult
import shuman.bsu.famcs.rag.knowledge.model.SourceSnapshot

/**
 * Источник знаний: сайт, таблица расписания, папка файлов.
 *
 * Новый тип источника подключается ещё одной реализацией; индексатор перебирает все источники и не знает, откуда документы.
 */
interface DataSource {
    /** Имя для логов и метрик. */
    val name: String

    /** Собирает все документы источника. */
    suspend fun snapshot(): SourceSnapshot

    /** Проверяет один адрес: нужно, чтобы убедиться, что пропавшая из обхода страница действительно удалена. */
    suspend fun fetch(url: String): FetchResult
}
