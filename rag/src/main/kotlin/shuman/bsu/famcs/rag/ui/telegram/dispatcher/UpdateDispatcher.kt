package shuman.bsu.famcs.rag.ui.telegram.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Запускает обработчики входящих обновлений: ограничивает их число и дожидается их при остановке.
 *
 * Ограничение: каждое сообщение любого человека (в том числе без согласия) запускает свою корутину ещё до очереди ядра.
 * При спаме или потоке сообщений, накопившихся за простой, их число и нагрузка на базу росли бы без предела. Сверх лимита
 * обновление отбрасывается: Telegram его уже выдал, повторно не пришлёт, а отвечать на поток всё равно нечем.
 *
 * Остановка: обработчики живут под отдельным родителем, а не под циклом приёма. Остановка цикла прекращает приём новых
 * сообщений, а [drain] ждёт тех, что уже в работе (человек уже спросил, Telegram подтвердил получение), и лишь затем отменяет оставшихся.
 */
class UpdateDispatcher(
    private val scope: CoroutineScope,
    maxConcurrent: Int,
    private val gracePeriod: Duration,
    /** Вызывается при отбрасывании обновления (для метрики). */
    private val onDropped: () -> Unit = {},
) {
    private val slots = Semaphore(maxConcurrent)
    private val handlers = SupervisorJob()
    private val closed = AtomicBoolean(false)

    /** Запускает [block] в отдельной корутине. `false` — обновление отброшено (остановка или нет свободных мест). */
    fun submit(
        name: String,
        block: suspend () -> Unit,
    ): Boolean {
        if (closed.get() || !slots.tryAcquire()) {
            onDropped()
            return false
        }
        scope.launch(handlers + CoroutineName(name)) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сбой одного обработчика не должен выйти за его границы и тем более остановить приём остальных.
                log.error(e) { "Обработчик обновления «$name» завершился ошибкой" }
            } finally {
                slots.release()
            }
        }
        return true
    }

    /** Прекращает приём, ждёт активных обработчиков не дольше срока ожидания, затем отменяет оставшихся. */
    suspend fun drain() {
        closed.set(true)
        val finished = withTimeoutOrNull(gracePeriod) {
            handlers.children.toList().forEach { it.join() }
            true
        }
        if (finished == null) log.warn { "Не все обработчики сообщений успели завершиться за $gracePeriod: оставшиеся отменяются" }
        handlers.cancel()
    }
}
