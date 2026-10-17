package shuman.bsu.famcs.rag.knowledge

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
import org.springframework.validation.annotation.Validated
import java.nio.file.Path
import java.time.Duration

/**
 * Настройки модуля знаний (`bot.knowledge` в application.yaml): нарезка, модели, поиск, Qdrant, краулер, индексация.
 * Каждое значение объяснено в application.yaml рядом с числом.
 */
@Validated
@ConfigurationProperties("bot.knowledge")
data class KnowledgeProperties(
    @field:Valid val chunking: Chunking,
    @field:Valid val embedding: Embedding,
    @field:Valid val sparse: Sparse,
    @field:Valid val search: Search,
    @field:Valid val reranker: Reranker,
    @field:Valid val qdrant: Qdrant,
    @field:Valid val crawler: Crawler,
    @field:Valid val indexing: Indexing,
) {
    data class Chunking(
        /** Размер дочернего фрагмента для поиска, символы. */
        @field:Positive val childSize: Int,
        /** Перекрытие соседних дочерних фрагментов, символы. */
        @field:Min(0) val childOverlap: Int,
        /** Разделители от крупного к мелкому. Пустая строка в конце — резать по символам. */
        @field:NotEmpty val childSeparators: List<String>,
        /** Заголовки до какого уровня делят страницу на родительские разделы (3 — `#`, `##`, `###`). */
        @field:Min(1) @field:Max(6) val headerLevels: Int,
        /** CSS-селекторы блока с основным содержимым страницы по порядку приоритета. */
        @field:NotEmpty val contentSelectors: List<String>,
        /** Теги, которые вырезаются из блока: скрипты, формы, навигация, шапка, подвал. */
        val removedTags: List<String>,
        val escapeAsterisks: Boolean,
        val escapeUnderscores: Boolean,
        /** Маркеры вложенных списков по уровням. */
        @field:NotBlank val bullets: String,
        /** Приводить относительные ссылки к абсолютным, чтобы ответ мог ссылаться на документ. */
        val absolutizeLinks: Boolean,
    )

    data class Embedding(
        /** Файл модели ONNX (BAAI/bge-m3). */
        val modelPath: Path,
        val tokenizerPath: Path,
        /** Размер вектора модели. У bge-m3 — 1024. */
        @field:Positive val dimension: Int,
        /** Длина входа в токенах. Что длиннее, обрезается. Дочерние фрагменты короче. */
        @field:Positive val maxTokens: Int,
        /** Сколько текстов считается за один проход модели. */
        @field:Positive val batchSize: Int,
        /** Потоки внутри одной операции модели. */
        @field:Positive val intraOpThreads: Int,
        /** Название выхода модели с вектором предложения. */
        @field:NotBlank val outputName: String,
    )

    enum class AnalyzerType {
        /** Как в Python-baseline: английские стоп-слова и стеммер, русские слова остаются как есть. */
        FASTEMBED_COMPATIBLE,

        /** Русский стеммер Snowball и русские стоп-слова. Эксперимент, не входит в паритет. */
        RUSSIAN,
    }

    data class Sparse(
        val analyzer: AnalyzerType,
        /** Параметр k BM25 (насыщение частоты терма). */
        @field:DecimalMin("0.1") val k: Double,
        /** Параметр b BM25 (влияние длины документа), от 0 до 1. */
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val b: Double,
        /** Средняя длина документа в термах (константа, как у FastEmbed). */
        @field:Positive val averageLength: Double,
        /** Термы длиннее этого пропускаются. */
        @field:Positive val maxTokenLength: Int,
        /** Включить модификатор IDF у sparse-вектора новых коллекций. В baseline был выключен. */
        val idfModifier: Boolean,
    )

    enum class FusionMode {
        /** Слияние результатов по рангу (RRF) без весов, как в baseline. */
        RRF,

        /** RRF с весами dense и sparse. Нужен Qdrant 1.17 и новее. Эксперимент. */
        WEIGHTED_RRF,
    }

    data class Search(
        /** Сколько кандидатов берёт каждый из двух поисков (по смыслу и по словам) до слияния. */
        @field:Positive val candidatesPerLeg: Int,
        /** Сколько кандидатов остаётся после слияния. */
        @field:Positive val resultLimit: Int,
        val fusion: FusionMode,
        /** Константа сглаживания RRF. Используется при WEIGHTED_RRF. */
        @field:Positive val rrfK: Int,
        /** Вес поиска по смыслу при WEIGHTED_RRF. */
        @field:DecimalMin("0.0") val denseWeight: Float,
        /** Вес поиска по словам при WEIGHTED_RRF. */
        @field:DecimalMin("0.0") val sparseWeight: Float,
        /** Ширина поиска в графе HNSW. Больше — точнее и медленнее. */
        @field:Positive val hnswEf: Int,
    )

    data class Reranker(
        val enabled: Boolean,
        val modelPath: Path,
        val tokenizerPath: Path,
        @field:Positive val maxTokens: Int,
        @field:Positive val batchSize: Int,
        @field:Positive val intraOpThreads: Int,
        @field:NotBlank val outputName: String,
        /** Сколько лучших фрагментов остаётся после реранкера и уходит в контекст. */
        @field:Positive val topN: Int,
    )

    data class Qdrant(
        @field:NotBlank val host: String,
        /** Порт gRPC (не REST). У Qdrant по умолчанию 6334. */
        @field:Positive val grpcPort: Int,
        val useTls: Boolean,
        val apiKey: String?,
        /** Имя, по которому ищет бот. За ним стоит физическая коллекция; при полной пересборке указатель переключается. */
        @field:NotBlank val collectionAlias: String,
        @field:NotBlank val denseVectorName: String,
        @field:NotBlank val sparseVectorName: String,
        /** Сколько точек отправляется в Qdrant одним запросом при записи. */
        @field:Positive val upsertBatchSize: Int,
        val requestTimeout: Duration,
        /** Число связей на узел графа HNSW. Больше — точнее и больше памяти. */
        @field:Positive val hnswM: Int,
        /** Ширина поиска при построении графа HNSW. */
        @field:Positive val hnswEfConstruct: Int,
        /** Сколько прежних коллекций хранить после переключения, чтобы можно было откатиться. */
        @field:Min(0) val keepOldCollections: Int,
    ) {
        /** Ключ Qdrant в журнал и сообщения об ошибках не попадает. */
        override fun toString() = "Qdrant(host=$host, grpcPort=$grpcPort, collectionAlias=$collectionAlias, apiKey=***)"
    }

    data class Crawler(
        /** Адрес, с которого начинается обход. Сайт определяется по домену этого адреса. */
        @field:NotBlank val startUrl: String,
        /** Максимальная глубина переходов по ссылкам. */
        @field:Min(0) val maxDepth: Int,
        /** Пауза между запросами к сайту. Вежливый обход: не чаще одного запроса в секунду. */
        val delayBetweenRequests: Duration,
        /** Представление краулера. Только ASCII: заголовки HTTP не допускают другие символы. */
        @field:Pattern(regexp = "\\p{ASCII}+", message = "User-Agent должен состоять из символов ASCII") val userAgent: String,
        val requestTimeout: Duration,
        /** Сколько раз повторять запрос при временном сбое. */
        @field:Min(0) val maxRetries: Int,
        val retryBackoff: Duration,
        /** Расширения файлов, которые не скачиваются как страницы. */
        val excludedExtensions: List<String>,
        /** Части пути, по которым ссылки пропускаются. */
        val excludedPathParts: List<String>,
        /** Читать `robots.txt` и не заходить в запрещённые разделы. */
        val respectRobotsTxt: Boolean,
        /** Дополнительные доверенные сертификаты (PEM), `classpath:` или `file:`. Нужны, если сайт отдаёт неполную цепочку сертификатов. */
        val extraCertificates: List<Resource>,
    )

    data class Indexing(
        /** Расписание ночной индексации (Spring cron: секунда минута час день месяц день_недели). */
        @field:NotBlank val cron: String,
        /** Запустить индексацию при старте, если база знаний пуста. Нужно при первом запуске и после очистки хранилища. */
        val indexOnEmptyStart: Boolean,
        /** Сколько страниц обрабатывается параллельно. Эмбеддинги на процессоре, поэтому немного. */
        @field:Positive val parallelPages: Int,
        /** Если ошибок больше этой доли страниц, обновление не применяется и остаётся прежняя версия. */
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val maxFailedShare: Double,
        /** Если страниц найдено меньше этой доли от прошлого раза, обновление не применяется (сайт мог упасть). */
        @field:DecimalMin("0.0") @field:DecimalMax("1.0") val minPagesShareOfPrevious: Double,
    )
}
