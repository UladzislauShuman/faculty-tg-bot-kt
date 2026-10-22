package shuman.bsu.famcs.rag.eval

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.TimeSource

/**
 * Строка прогресса долгой проверки в консоль: время, этап, «N из M», процент, прошедшее время и оценка остатка.
 * По ней видно, идёт ли прогон и сколько ждать (консоль теста выводится Gradle, см. `testLogging` в `build.gradle.kts`).
 *
 * @property stage название этапа в строке («Судья», «Сбор ответов»).
 * @property total сколько шагов всего.
 * @property every через сколько шагов писать строку; первая и последняя пишутся всегда.
 */
class EvalProgress(
    private val stage: String,
    private val total: Int,
    private val every: Int = DEFAULT_EVERY,
) {
    private val started = TimeSource.Monotonic.markNow()
    private var done = 0

    init {
        println("${now()} $stage: начало, шагов $total")
    }

    /**
     * Отмечает выполненный шаг.
     *
     * @param note добавка к строке (например, текущая доля верных ответов); вычисляется только когда строка пишется.
     */
    fun step(note: () -> String = { "" }) {
        done++
        if (done != 1 && done != total && done % every != 0) return
        val elapsed = started.elapsedNow().inWholeSeconds
        val remaining = if (done == 0) 0 else elapsed * (total - done) / done
        val extra = note().let { if (it.isBlank()) "" else "; $it" }
        println(
            "${now()} $stage: $done из $total (${100 * done / total.coerceAtLeast(1)}%), прошло ${minutes(elapsed)}, " +
                "осталось ≈ ${minutes(remaining)}$extra",
        )
    }

    /** Пишет итоговую строку этапа. */
    fun finish(note: String = "") {
        println("${now()} $stage: готово за ${minutes(started.elapsedNow().inWholeSeconds)}${if (note.isBlank()) "" else "; $note"}")
    }

    private fun minutes(seconds: Long) = "${seconds / 60} мин ${seconds % 60} с"

    private fun now() = "[${LocalTime.now().format(TIME)}]"

    private companion object {
        const val DEFAULT_EVERY = 10
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
