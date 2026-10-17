package shuman.bsu.famcs.rag.platform.configuration

import kotlinx.coroutines.Dispatchers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import shuman.bsu.famcs.rag.platform.configuration.properties.DatabaseProperties
import shuman.bsu.famcs.rag.platform.configuration.properties.RuntimeProperties
import shuman.bsu.famcs.rag.platform.database.Database
import shuman.bsu.famcs.rag.platform.runtime.AppDispatchers
import shuman.bsu.famcs.rag.platform.runtime.ApplicationScope

/**
 * Сборка среды выполнения: пулы потоков, область фоновых задач и доступ к базе данных.
 */
@Configuration(proxyBeanMethods = false)
class RuntimeConfiguration {
    /** Пулы потоков: вычисления на процессоре и блокирующие вызовы базы данных. */
    @Bean
    fun appDispatchers(
        runtime: RuntimeProperties,
        database: DatabaseProperties,
    ) = AppDispatchers(
        cpu = Dispatchers.Default.limitedParallelism(runtime.cpuThreads),
        database = Dispatchers.IO.limitedParallelism(database.maxParallelQueries),
    )

    /** Область корутин приложения для фоновых задач; при остановке ждёт их до `shutdownGracePeriod`. */
    @Bean
    fun applicationScope(properties: RuntimeProperties) = ApplicationScope(gracePeriod = properties.shutdownGracePeriod)

    /** Доступ к базе данных: блокирующий JDBC на отдельном пуле, транзакции. */
    @Bean
    fun database(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        dispatchers: AppDispatchers,
    ) = Database(jdbc = jdbc, transactions = TransactionTemplate(transactionManager), dispatcher = dispatchers.database)
}
