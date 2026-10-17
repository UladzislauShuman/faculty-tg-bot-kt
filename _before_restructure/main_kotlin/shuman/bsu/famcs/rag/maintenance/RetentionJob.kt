package shuman.bsu.famcs.rag.maintenance

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.validation.annotation.Validated
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.dialog.ConversationStore
import shuman.bsu.famcs.rag.limits.LimitsProperties
import shuman.bsu.famcs.rag.limits.MinuteWindow
import shuman.bsu.famcs.rag.limits.ProcessedUpdates
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/** Сроки хранения данных (`bot.retention`). Обещание пользователю из текста согласия: дольше эти данные не живут. */
@Validated
@ConfigurationProperties("bot.retention")
data class RetentionProperties(
    /** Сколько дней хранятся тексты диалогов (вопросы и ответы). */
    @field:Positive val messagesDays: Int,
    /** Сколько дней хранится журнал ответов вместе с запросами и оценками. */
    @field:Positive val answerLogDays: Int,
    /** Сколько дней хранятся суточные счётчики пользователей. Счётчики системы (по одной строке в сутки) не удаляются. */
    @field:Positive val usageDays: Int,
    /** Когда запускается суточная очистка (cron Spring, шесть полей, по часовому поясу бота). */
    @field:NotBlank val cron: String,
    /** Как часто память процесса очищается от давно не писавших пользователей. */
    val memorySweepInterval: Duration,
)

/** Удаление устаревших записей, которых нет в других хранилищах. */
interface RetentionStore {
    /** Удаляет записи журнала ответов старше [cutoff] вместе с их оценками. */
    suspend fun deleteAnswerLogOlderThan(cutoff: Instant): Int

    /** Удаляет суточные счётчики пользователей до [day]. */
    suspend fun deleteUsageBefore(day: LocalDate): Int

    /** Удаляет сессии без сообщений, где давно не было активности. */
    suspend fun deleteEmptySessionsOlderThan(cutoff: Instant): Int
}

class JdbcRetentionStore(
    private val db: Database,
) : RetentionStore {
    override suspend fun deleteAnswerLogOlderThan(cutoff: Instant): Int = db.query { jdbc ->
        jdbc.sql("DELETE FROM answer_log WHERE created_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update()
    }

    override suspend fun deleteUsageBefore(day: LocalDate): Int = db.query { jdbc ->
        jdbc.sql("DELETE FROM usage_counter WHERE day < :day").param("day", Date.valueOf(day)).update()
    }

    override suspend fun deleteEmptySessionsOlderThan(cutoff: Instant): Int = db.query { jdbc ->
        jdbc
            .sql(
                "DELETE FROM chat_session s WHERE s.last_activity_at < :cutoff AND NOT EXISTS (SELECT 1 FROM message m WHERE m.session_id = s.id)",
            ).param("cutoff", Timestamp.from(cutoff))
            .update()
    }
}

/**
 * Плановая очистка: сроки хранения данных и память процесса.
 *
 * Каждый шаг независим: сбой одного (например, блокировка таблицы) не мешает остальным и повторится в следующий запуск.
 */
class RetentionJob(
    private val conversations: ConversationStore,
    private val processed: ProcessedUpdates,
    private val store: RetentionStore,
    private val minuteWindow: MinuteWindow,
    private val retention: RetentionProperties,
    private val updates: LimitsProperties.Updates,
    private val clock: Clock,
) {
    /** Суточная очистка по cron из `bot.retention.cron`. */
    @Scheduled(cron = "\${bot.retention.cron}", zone = "\${bot.time-zone}")
    fun runDaily() = runBlocking { cleanUp() }

    /** Выбрасывает из памяти пользователей, давно не писавших: иначе окно лимита растёт вместе с числом пользователей. */
    @Scheduled(fixedDelayString = "\${bot.retention.memory-sweep-interval}")
    fun sweepMemory() = minuteWindow.evictIdle(clock.instant())

    suspend fun cleanUp() {
        val now = clock.instant()
        step("сообщения") { conversations.deleteMessagesOlderThan(now.minus(Duration.ofDays(retention.messagesDays.toLong()))) }
        step("журнал ответов") { store.deleteAnswerLogOlderThan(now.minus(Duration.ofDays(retention.answerLogDays.toLong()))) }
        step("пустые сессии") { store.deleteEmptySessionsOlderThan(now.minus(Duration.ofDays(retention.messagesDays.toLong()))) }
        step("счётчики") { store.deleteUsageBefore(LocalDate.now(clock).minusDays(retention.usageDays.toLong())) }
        step("номера обновлений") { processed.purgeOlderThan(now.minus(updates.retention)) }
    }

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
