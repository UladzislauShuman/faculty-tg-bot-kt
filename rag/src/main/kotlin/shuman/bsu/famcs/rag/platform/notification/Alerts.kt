package shuman.bsu.famcs.rag.platform.notification

import shuman.bsu.famcs.rag.platform.configuration.properties.AlertsProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Оповещения администраторов о сбоях: исчерпана квота провайдера, недоступны все модели, внутренняя ошибка.
 * Одно оповещение с одним ключом не чаще раза в [AlertsProperties.minInterval].
 */
class Alerts(
    private val notifier: AdminNotifier,
    private val clock: Clock,
    private val minInterval: Duration,
) {
    private val lastSent = ConcurrentHashMap<String, Instant>()

    /** Отправляет оповещение, если с ключом [key] оно давно не отправлялось. Ключ определяет «вид» сбоя. */
    suspend fun raise(
        key: String,
        text: String,
    ) {
        val now = clock.instant()
        var send = false
        lastSent.compute(key) { _, previous ->
            if (previous == null || Duration.between(previous, now) >= minInterval) {
                send = true
                now
            } else {
                previous
            }
        }
        if (send) notifier.notifyAdmins(text)
    }
}
