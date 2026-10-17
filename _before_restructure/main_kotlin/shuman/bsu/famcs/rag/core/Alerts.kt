package shuman.bsu.famcs.rag.core

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Настройки оповещений (`bot.alerts`). */
@Validated
@ConfigurationProperties("bot.alerts")
data class AlertsProperties(
    /** Как часто повторяется оповещение одного вида. Сбой, который длится часами, не должен засыпать администратора сообщениями. */
    @field:NotNull val minInterval: Duration,
)

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
