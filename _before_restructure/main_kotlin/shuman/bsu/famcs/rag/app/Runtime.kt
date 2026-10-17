package shuman.bsu.famcs.rag.app

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.constraints.Positive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.DatabaseProperties
import java.time.Duration

private val log = KotlinLogging.logger {}

/** Настройки среды выполнения (`bot.runtime`). */
@ConfigurationProperties("bot.runtime")
data class RuntimeProperties(
    /** Сколько потоков отдано вычислениям моделей на процессоре (эмбеддинги и реранкер вместе). */
    @field:Positive val cpuThreads: Int,
    /** Сколько ждать завершения фоновых задач при остановке приложения. */
    val shutdownGracePeriod: Duration,
)

/**
 * Диспетчеры корутин приложения.
 *
 * `cpu` — вычисления моделей на процессоре; число потоков ограничено, чтобы эмбеддинги и реранкер не отбирали
 * все ядра у приложения (на домашнем ПК их четыре). `database` — блокирующие вызовы JDBC; потоков не больше, чем соединений в пуле.
 */
class AppDispatchers(
    val cpu: CoroutineDispatcher,
    val database: CoroutineDispatcher,
)

/**
 * Область корутин приложения: у каждой фоновой задачи есть владелец и точка остановки.
 *
 * `SupervisorJob`: сбой одной фоновой задачи (индексация, обработка сообщения) не отменяет остальные.
 * Необработанное исключение логируется, а не пропадает молча.
 */
class ApplicationScope(
    private val gracePeriod: Duration,
) : CoroutineScope, DisposableBean {
    override val coroutineContext =
        SupervisorJob() + Dispatchers.Default + CoroutineName("bot") +
            CoroutineExceptionHandler { _, error -> log.error(error) { "Необработанная ошибка в фоновой задаче" } }

    override fun destroy() {
        val job = coroutineContext[Job] ?: return
        // Долгоживущие циклы (приём сообщений, воркеры) останавливаются своими владельцами раньше: Spring уничтожает
        // зависимые бины до этого. Здесь даём доработать остальным задачам и отменяем то, что не успело.
        runBlocking { withTimeoutOrNull(gracePeriod.toMillis()) { job.children.forEach { it.join() } } }
        cancel("приложение останавливается")
    }

    /** Запускает фоновую задачу в области приложения. */
    fun launchTask(
        name: String,
        block: suspend () -> Unit,
    ) = launch(CoroutineName(name)) { block() }
}

@Configuration(proxyBeanMethods = false)
class RuntimeConfiguration {
    @Bean
    fun appDispatchers(
        runtime: RuntimeProperties,
        database: DatabaseProperties,
    ) = AppDispatchers(
        cpu = Dispatchers.Default.limitedParallelism(runtime.cpuThreads),
        database = Dispatchers.IO.limitedParallelism(database.maxParallelQueries),
    )

    @Bean
    fun applicationScope(properties: RuntimeProperties) = ApplicationScope(gracePeriod = properties.shutdownGracePeriod)

    @Bean
    fun database(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        dispatchers: AppDispatchers,
    ) = Database(jdbc = jdbc, transactions = TransactionTemplate(transactionManager), dispatcher = dispatchers.database)
}
