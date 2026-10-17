package shuman.bsu.famcs.rag.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.testsupport.MutableClock
import shuman.bsu.famcs.rag.testsupport.RecordingNotifier
import java.time.Duration
import kotlin.test.assertEquals

class AlertsTest {
    private val clock = MutableClock()
    private val notifier = RecordingNotifier()
    private val alerts = Alerts(notifier, clock, Duration.ofHours(1))

    @Test
    fun `одно и то же оповещение не чаще раза за интервал`() = runTest {
        alerts.raise("quota", "первое")
        alerts.raise("quota", "второе")
        clock.advance(Duration.ofMinutes(59))
        alerts.raise("quota", "третье")

        assertEquals(listOf("первое"), notifier.messages)
    }

    @Test
    fun `после интервала оповещение отправляется снова`() = runTest {
        alerts.raise("quota", "первое")
        clock.advance(Duration.ofHours(1))
        alerts.raise("quota", "снова")

        assertEquals(listOf("первое", "снова"), notifier.messages)
    }

    @Test
    fun `разные виды сбоев не мешают друг другу`() = runTest {
        alerts.raise("quota", "квота")
        alerts.raise("auth", "ключ")

        assertEquals(listOf("квота", "ключ"), notifier.messages)
    }
}
