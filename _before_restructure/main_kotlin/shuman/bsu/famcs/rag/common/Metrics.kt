package shuman.bsu.famcs.rag.common

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit

/**
 * Замеряет время работы блока и записывает его в таймер [name] с тегами [tags] (пары «имя, значение»).
 * Время пишется и при ошибке: медленный отказ тоже важен для p95.
 */
suspend fun <T> MeterRegistry.timed(
    name: String,
    vararg tags: String,
    block: suspend () -> T,
): T {
    val started = System.nanoTime()
    try {
        return block()
    } finally {
        timer(name, *tags).record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
    }
}
