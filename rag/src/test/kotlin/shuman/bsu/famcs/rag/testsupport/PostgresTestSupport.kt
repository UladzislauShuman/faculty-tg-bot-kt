package shuman.bsu.famcs.rag.testsupport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import shuman.bsu.famcs.rag.platform.database.Database

/**
 * Настоящий PostgreSQL для интеграционных тестов: контейнер той же мажорной версии, что и в боевой конфигурации,
 * схема накатывается теми же миграциями Flyway, что и в приложении.
 */
object TestPostgres {
    const val IMAGE = "postgres:16-alpine"

    fun container() = PostgreSQLContainer(IMAGE)

    /** Накатывает миграции и возвращает [Database] с тем же устройством, что и в приложении. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun database(container: PostgreSQLContainer): Database {
        val dataSource = DriverManagerDataSource(container.jdbcUrl, container.username, container.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return Database(
            jdbc = JdbcClient.create(dataSource),
            transactions = TransactionTemplate(DataSourceTransactionManager(dataSource)),
            dispatcher = Dispatchers.IO.limitedParallelism(4),
        )
    }
}
