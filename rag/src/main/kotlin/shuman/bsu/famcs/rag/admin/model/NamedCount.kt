package shuman.bsu.famcs.rag.admin.model

/**
 * Количество записей в одной группе: статус ответа или ветка.
 *
 * @property name название группы (например, `ANSWERED` или `RAG`).
 * @property count сколько записей журнала попало в группу.
 */
data class NamedCount(
    val name: String,
    val count: Int,
)
