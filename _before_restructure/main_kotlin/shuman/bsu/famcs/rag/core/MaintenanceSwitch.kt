package shuman.bsu.famcs.rag.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Режим обслуживания: пока включён, бот отвечает всем одним текстом и не обращается к моделям.
 * Начальное состояние берётся из `bot.maintenance-enabled`, администратор переключает его командой без перезапуска.
 */
class MaintenanceSwitch(
    initial: Boolean,
) {
    private val flag = AtomicBoolean(initial)

    var enabled: Boolean
        get() = flag.get()
        set(value) = flag.set(value)
}
