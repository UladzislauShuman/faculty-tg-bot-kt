package shuman.bsu.famcs.rag.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import shuman.bsu.famcs.rag.common.Database
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.core.MaintenanceSwitch
import shuman.bsu.famcs.rag.knowledge.indexing.IndexMode
import shuman.bsu.famcs.rag.knowledge.indexing.IndexingJob
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/** Настройки административных команд (`bot.admin`). */
@Validated
@ConfigurationProperties("bot.admin")
data class AdminProperties(
    /** За какой период считается статистика `/admin_stats`. */
    val statsWindow: Duration,
    /** Период, за который собираются вопросы без ответа в `/admin_gaps`. */
    val gapsWindow: Duration,
    /** Сколько строк показывать в списках `/admin_feedback` и `/admin_gaps`. */
    @field:Positive val listLimit: Int,
    /** Сколько символов вопроса и ответа показывать в разборе низких оценок. */
    @field:Positive val excerptChars: Int,
)

/** Административные команды для адаптера: он проверяет права, а выполнение и аудит остаются здесь. */
fun interface AdminCommands {
    suspend fun execute(
        adminId: TelegramId,
        command: String,
        args: List<String>,
    ): String
}

/**
 * Административные команды: статистика, низкие оценки, пробелы в знаниях, переиндексация, режим обслуживания.
 *
 * Возвращает готовый текст. Проверка, что вызывает администратор, делается в адаптере; каждый вызов пишется в `admin_audit`.
 * Админ видит запросы пользователей (они хранятся в журнале ограниченное время), но не Telegram ID.
 */
class AdminService(
    private val db: Database,
    private val indexing: IndexingJob,
    private val maintenance: MaintenanceSwitch,
    private val properties: AdminProperties,
    private val clock: Clock,
) : AdminCommands {
    /** Команды, которые понимает сервис. Используются в справке при неизвестной команде. */
    private val commands: List<String> = listOf("reindex", "stats", "feedback", "gaps", "maintenance")

    override suspend fun execute(
        adminId: TelegramId,
        command: String,
        args: List<String>,
    ): String {
        audit(adminId, command)
        log.info { "Административная команда: $command" }
        return when (command) {
            "reindex" -> reindex(args.firstOrNull())
            "stats" -> stats()
            "feedback" -> lowRated()
            "gaps" -> gaps()
            "maintenance" -> switchMaintenance(args.firstOrNull())
            else -> "Неизвестная команда. Доступно: ${commands.joinToString(", ") { "/admin_$it" }}"
        }
    }

    /**
     * Без аргумента — обычное обновление (меняются только изменившиеся страницы). `full` — полная пересборка в новую коллекцию:
     * нужна после смены модели эмбеддингов или правил нарезки, которые обычное обновление не замечает.
     */
    private fun reindex(argument: String?): String {
        val mode = if (argument.equals("full", ignoreCase = true)) IndexMode.FULL else IndexMode.INCREMENTAL
        return if (indexing.start(mode, reportSuccess = true)) {
            "Индексация ($mode) запущена. Итог придёт отдельным сообщением."
        } else {
            "Индексация уже идёт."
        }
    }

    private fun switchMaintenance(argument: String?): String = when (argument?.lowercase()) {
        "on" -> "Режим обслуживания включён.".also { maintenance.enabled = true }
        "off" -> "Режим обслуживания выключен.".also { maintenance.enabled = false }
        else -> "Режим обслуживания: ${if (maintenance.enabled) "включён" else "выключен"}. Команда: /admin_maintenance on|off"
    }

    private suspend fun stats(): String {
        val since = Timestamp.from(clock.instant().minus(properties.statsWindow))
        return db.query { jdbc ->
            val users = jdbc
                .sql("SELECT count(*) AS total, count(*) FILTER (WHERE last_seen_at > :since) AS recent FROM app_user")
                .param("since", since)
                .query { rs, _ -> rs.getInt("total") to rs.getInt("recent") }
                .single()
            val byStatus = jdbc
                .sql(
                    "SELECT status, count(*) AS n FROM answer_log WHERE created_at > :since GROUP BY status ORDER BY n DESC",
                ).param("since", since)
                .query { rs, _ -> "${rs.getString("status")}: ${rs.getInt("n")}" }
                .list()
            val byRoute = jdbc
                .sql(
                    "SELECT route, count(*) AS n FROM answer_log WHERE created_at > :since AND status IN ('ANSWERED', 'NO_INFO') GROUP BY route ORDER BY n DESC",
                ).param("since", since)
                .query { rs, _ -> "${rs.getString("route")}: ${rs.getInt("n")}" }
                .list()
            val quality = jdbc
                .sql(
                    """
                SELECT count(*) AS answered,
                       count(*) FILTER (WHERE no_info) AS no_info,
                       coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms), 0) AS p50,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95,
                       coalesce(sum(tokens_in), 0) AS tokens_in, coalesce(sum(tokens_out), 0) AS tokens_out
                FROM answer_log WHERE created_at > :since AND status IN ('ANSWERED', 'NO_INFO')
                    """.trimIndent(),
                ).param("since", since)
                .query { rs, _ ->
                    val answered = rs.getInt("answered")
                    listOf(
                        "Ответов: $answered, из них «не нашёл»: ${rs.getInt("no_info")}",
                        "Время ответа p50 / p95: ${rs.getLong("p50") / MS} / ${rs.getLong("p95") / MS} с",
                        "Токены вход / выход: ${rs.getLong("tokens_in")} / ${rs.getLong("tokens_out")}",
                    )
                }.single()
            val rating = jdbc
                .sql("SELECT count(*) AS n, coalesce(round(avg(rating), 2), 0) AS avg FROM feedback WHERE created_at > :since")
                .param("since", since)
                .query { rs, _ -> "Оценок: ${rs.getInt("n")}, средняя: ${rs.getBigDecimal("avg")}" }
                .single()

            buildString {
                appendLine("Статистика за ${describe(properties.statsWindow)}")
                appendLine("Пользователей: ${users.first}, активных: ${users.second}")
                appendLine("Статусы: ${byStatus.joinToString(", ").ifEmpty { "нет данных" }}")
                appendLine("Ветки: ${byRoute.joinToString(", ").ifEmpty { "нет данных" }}")
                quality.forEach(::appendLine)
                append(rating)
            }
        }
    }

    private suspend fun lowRated(): String = db.query { jdbc ->
        val rows = jdbc
            .sql(
                """
            SELECT f.rating, f.reason, f.comment, a.rewritten_query, m.body
            FROM feedback f JOIN answer_log a ON a.id = f.answer_id LEFT JOIN message m ON m.id = a.message_id
            WHERE f.rating <= 2 ORDER BY f.created_at DESC LIMIT :limit
                """.trimIndent(),
            ).param("limit", properties.listLimit)
            .query { rs, _ ->
                buildString {
                    append("Оценка ${rs.getInt("rating")}")
                    rs.getString("reason")?.let { append(" ($it)") }
                    rs.getString("comment")?.let { append(", комментарий: ${excerpt(it)}") }
                    rs.getString("rewritten_query")?.let { append("\nЗапрос: ${excerpt(it)}") }
                    rs.getString("body")?.let { append("\nОтвет: ${excerpt(it)}") }
                }
            }.list()
        if (rows.isEmpty()) "Низких оценок нет." else rows.joinToString("\n\n")
    }

    private suspend fun gaps(): String {
        val since = Timestamp.from(clock.instant().minus(properties.gapsWindow))
        return db.query { jdbc ->
            val rows = jdbc
                .sql(
                    """
                SELECT rewritten_query, count(*) AS n FROM answer_log
                WHERE no_info AND rewritten_query IS NOT NULL AND created_at > :since
                GROUP BY rewritten_query ORDER BY n DESC, max(created_at) DESC LIMIT :limit
                    """.trimIndent(),
                ).param("since", since)
                .param("limit", properties.listLimit)
                .query { rs, _ -> "${rs.getInt("n")} × ${excerpt(rs.getString("rewritten_query"))}" }
                .list()
            if (rows.isEmpty()) "Вопросов без ответа за ${describe(properties.gapsWindow)} нет." else rows.joinToString("\n")
        }
    }

    private suspend fun audit(
        adminId: TelegramId,
        command: String,
    ) {
        db.query { jdbc ->
            jdbc
                .sql("INSERT INTO admin_audit (admin_id, command, at) VALUES (:admin, :command, :at)")
                .param("admin", adminId.value)
                .param("command", command)
                .param("at", Timestamp.from(clock.instant()))
                .update()
        }
    }

    private fun excerpt(text: String) = if (text.length <= properties.excerptChars) text else text.take(properties.excerptChars) + "…"

    private fun describe(window: Duration) = if (window.toHours() >=
        SWITCH_TO_DAYS_AFTER_HOURS
    ) {
        "${window.toDays()} дн."
    } else {
        "${window.toHours()} ч."
    }

    private companion object {
        /** Перевод миллисекунд в секунды для вывода времени ответа. */
        const val MS = 1000
        const val SWITCH_TO_DAYS_AFTER_HOURS = 48
    }
}
