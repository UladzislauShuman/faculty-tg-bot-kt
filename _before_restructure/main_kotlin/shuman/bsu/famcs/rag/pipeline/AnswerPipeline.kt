package shuman.bsu.famcs.rag.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.timed
import shuman.bsu.famcs.rag.dialog.ContextManager
import shuman.bsu.famcs.rag.dialog.DialogContext
import shuman.bsu.famcs.rag.dialog.QueryRewriter
import shuman.bsu.famcs.rag.intent.HandlerInput
import shuman.bsu.famcs.rag.intent.HandlerOutput
import shuman.bsu.famcs.rag.intent.Intent
import shuman.bsu.famcs.rag.intent.IntentClassifier
import shuman.bsu.famcs.rag.intent.IntentHandler

private val log = KotlinLogging.logger {}

/** Что получилось из вопроса: контекст диалога, выбранная ветка, запрос для поиска и ответ обработчика. */
data class PipelineResult(
    val context: DialogContext,
    val intent: Intent,
    /** Самостоятельная формулировка вопроса (равна исходному, если переформулировки не было). */
    val query: String,
    val output: HandlerOutput,
)

/**
 * Явный оркестратор обработки вопроса: контекст → переформулирование → классификатор → обработчик ветки.
 *
 * Порядок шагов виден в одном месте и меняется правкой этого класса, без графа и скрытых переходов (решение автора:
 * простота и наглядность важнее гибкости фреймворка). Каждый шаг за интерфейсом, поэтому подменяется для эксперимента.
 * Классификатор стоит после переформулирования: он видит самостоятельный вопрос, а не «а там какой балл?».
 */
class AnswerPipeline(
    private val contexts: ContextManager,
    private val rewriter: QueryRewriter,
    private val classifier: IntentClassifier,
    handlers: List<IntentHandler>,
    private val meters: MeterRegistry,
) {
    private val handlers: Map<Intent, IntentHandler> = handlers.associateBy { it.intent }

    init {
        val missing = Intent.entries.filter { it !in this.handlers }
        require(missing.isEmpty()) { "Нет обработчиков для веток: $missing" }
        require(handlers.size == this.handlers.size) { "Для одной ветки задано несколько обработчиков" }
    }

    suspend fun run(
        userId: TelegramId,
        question: String,
    ): PipelineResult {
        val context = meters.timed("bot.stage", "stage", "context") { contexts.open(userId) }
        val query = meters.timed("bot.stage", "stage", "rewrite") { rewriter.rewrite(question, context) }
        val intent = meters.timed("bot.stage", "stage", "classify") { classifier.classify(query) }
        log.debug { "Ветка: $intent (переформулировано: ${query != question})" }
        val output = meters.timed("bot.stage", "stage", "handle.${intent.name.lowercase()}") {
            handlers.getValue(intent).handle(HandlerInput(question = question, query = query, context = context))
        }
        return PipelineResult(context = context, intent = intent, query = query, output = output)
    }
}
