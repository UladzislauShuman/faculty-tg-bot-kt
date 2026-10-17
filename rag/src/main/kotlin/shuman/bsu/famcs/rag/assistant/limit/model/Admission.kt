package shuman.bsu.famcs.rag.assistant.limit.model

/** Результат попытки выполнить работу под защитой лимитов. */
sealed interface Admission<out T> {
    data class Denied(val denial: Denial) : Admission<Nothing>

    data class Completed<T>(val value: T) : Admission<T>
}
