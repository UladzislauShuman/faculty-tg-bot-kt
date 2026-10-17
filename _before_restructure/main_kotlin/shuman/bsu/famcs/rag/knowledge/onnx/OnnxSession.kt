package shuman.bsu.famcs.rag.knowledge.onnx

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.file.Files
import java.nio.file.Path

/**
 * Общая часть моделей на ONNX Runtime: сессия вычислений на процессоре и токенизатор HuggingFace.
 *
 * Зачем свой тонкий слой, а не готовые классы LangChain4j: нужны обе выходные формы (вектор предложения, логит пары),
 * правильная обработка токенизатора XLM-RoBERTa (sentencepiece) и явные настройки потоков. На домашнем ПК с четырьмя
 * ядрами число потоков вычислений определяет, остаётся ли место остальному приложению.
 */
class OnnxSession(
    modelPath: Path,
    tokenizerPath: Path,
    /** Максимальная длина входа в токенах. Что длиннее, обрезается. */
    maxTokens: Int,
    /** Потоки внутри одной операции модели. На четырёх ядрах разумно 2–3: остальное нужно приложению. */
    intraOpThreads: Int,
) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    val session: OrtSession
    val tokenizer: HuggingFaceTokenizer

    /** Имена входов модели: у BERT три (`token_type_ids` в том числе), у XLM-RoBERTa два. */
    val inputNames: Set<String>

    init {
        require(Files.isRegularFile(modelPath)) { "Файл модели не найден: $modelPath. См. scripts/prepare-models.sh" }
        require(Files.isRegularFile(tokenizerPath)) { "Файл токенизатора не найден: $tokenizerPath" }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            // Одна операция за раз: параллельность задаётся числом одновременных запросов, а не графом модели.
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            setCPUArenaAllocator(true)
        }
        session = environment.createSession(modelPath.toString(), options)
        inputNames = session.inputNames
        tokenizer = HuggingFaceTokenizer
            .builder()
            .optTokenizerPath(tokenizerPath)
            // Служебные токены (начало и конец) нужны модели так же, как при обучении.
            .optAddSpecialTokens(true)
            // В пакете короткие тексты дополняются до самого длинного: вход становится прямоугольным.
            .optPadding(true)
            .optTruncation(true)
            .optMaxLength(maxTokens)
            .build()
    }

    fun newTensor(data: Array<LongArray>) = ai.onnxruntime.OnnxTensor.createTensor(environment, data)

    override fun close() {
        tokenizer.close()
        session.close()
    }
}
