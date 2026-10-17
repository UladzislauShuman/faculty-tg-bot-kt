package shuman.bsu.famcs.rag.assistant.dialog.model

/** Реплика диалога без служебных полей: то, что видит модель. */
data class DialogTurn(
    val author: Author,
    val text: String,
)
