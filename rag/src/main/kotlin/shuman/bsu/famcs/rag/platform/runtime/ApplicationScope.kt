package shuman.bsu.famcs.rag.platform.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.time.Duration

private val log = KotlinLogging.logger {}

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
