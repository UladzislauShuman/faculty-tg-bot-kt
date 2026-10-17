package shuman.bsu.famcs.rag.assistant.intent.classifier.implementation

import shuman.bsu.famcs.rag.assistant.intent.classifier.IntentClassifier
import shuman.bsu.famcs.rag.assistant.intent.model.Intent

/** Классификатор выключен: все вопросы идут в RAG, как в базовой версии. */
object AlwaysRagClassifier : IntentClassifier {
    override suspend fun classify(query: String) = Intent.RAG
}
