package shuman.bsu.famcs.rag.platform.runtime

import kotlinx.coroutines.CoroutineDispatcher

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
