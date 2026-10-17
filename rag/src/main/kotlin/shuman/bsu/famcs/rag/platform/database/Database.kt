package shuman.bsu.famcs.rag.platform.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * Мост между корутинами и блокирующим JDBC.
 *
 * Драйвер PostgreSQL блокирует поток на время запроса, поэтому каждый запрос уходит на отдельный диспетчер с ограниченным
 * числом потоков (равным пулу соединений). Тело транзакции — обычный блок без приостановок: транзакция JDBC привязана к потоку,
 * а `suspend`-вызов внутри мог бы продолжиться на другом потоке.
 */
class Database(
    val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val dispatcher: CoroutineDispatcher,
) {
    /** Выполняет чтение или одиночное изменение. */
    suspend fun <T> query(block: (JdbcClient) -> T): T = withContext(dispatcher) { block(jdbc) }

    /** Выполняет несколько операций в одной транзакции: либо все, либо ни одной. */
    suspend fun <T> transaction(block: (JdbcClient) -> T): T = withContext(dispatcher) {
        transactions.execute { block(jdbc) }
    }
}
