package shuman.bsu.famcs.rag.admin.maintenance

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import shuman.bsu.famcs.rag.admin.maintenance.repository.RetentionRepository
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.dialog.repository.ConversationRepository
import shuman.bsu.famcs.rag.assistant.limit.repository.ProcessedUpdateRepository
import shuman.bsu.famcs.rag.assistant.limit.service.MinuteWindow
import shuman.bsu.famcs.rag.platform.configuration.properties.RetentionProperties
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Плановая очистка: сроки хранения данных и память процесса.
 *
 * Каждый шаг независим: сбой одного (например, блокировка таблицы) не мешает остальным и повторится в следующий запуск.
 */
class RetentionJob(
    private val conversations: ConversationRepository,
    private val processed: ProcessedUpdateRepository,
    private val store: RetentionRepository,
    private val minuteWindow: MinuteWindow,
    private val retention: RetentionProperties,
    private val updates: LimitProperties.Updates,
    private val clock: Clock,
) {
    /** Суточная очистка по cron из `bot.retention.cron`. */
    @Scheduled(cron = "\${bot.retention.cron}", zone = "\${bot.time-zone}")
    fun runDaily() = runBlocking { cleanUp() }

    /** Выбрасывает из памяти пользователей, давно не писавших: иначе окно лимита растёт вместе с числом пользователей. */
    @Scheduled(fixedDelayString = "\${bot.retention.memory-sweep-interval}")
    fun sweepMemory() = minuteWindow.evictIdle(clock.instant())

    /**
     * Удаляет данные, у которых вышел срок хранения: сообщения, журнал ответов, пустые сессии, счётчики, номера обновлений.
     * Каждый шаг независим: сбой одного не мешает остальным.
     */
    suspend fun cleanUp() {
        val now = clock.instant()
        step("сообщения") { conversations.deleteMessagesOlderThan(now.minus(Duration.ofDays(retention.messagesDays.toLong()))) }
        step("журнал ответов") { store.deleteAnswerLogOlderThan(now.minus(Duration.ofDays(retention.answerLogDays.toLong()))) }
        step("пустые сессии") { store.deleteEmptySessionsOlderThan(now.minus(Duration.ofDays(retention.messagesDays.toLong()))) }
        step("счётчики") { store.deleteUsageBefore(LocalDate.now(clock).minusDays(retention.usageDays.toLong())) }
        step("номера обновлений") { processed.purgeOlderThan(now.minus(updates.retention)) }
    }

    /**
     * Выполняет один шаг очистки и пишет в журнал, сколько записей удалено; ошибка шага журналируется и не прерывает очистку.
     *
     * @param name название шага для журнала.
     * @param block удаление; возвращает число удалённых записей.
     */
    private suspend fun step(
        name: String,
        block: suspend () -> Int,
    ) {
        try {
            val removed = block()
            log.info { "Очистка «$name»: удалено $removed" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Очистка «$name» не удалась" }
        }
    }
}
