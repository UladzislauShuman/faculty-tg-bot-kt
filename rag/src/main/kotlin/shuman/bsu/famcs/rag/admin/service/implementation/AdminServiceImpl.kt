package shuman.bsu.famcs.rag.admin.service.implementation

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.admin.configuration.properties.AdminProperties
import shuman.bsu.famcs.rag.admin.repository.AdminAuditRepository
import shuman.bsu.famcs.rag.admin.repository.StatisticsRepository
import shuman.bsu.famcs.rag.api.AdminService
import shuman.bsu.famcs.rag.assistant.pipeline.service.MaintenanceSwitch
import shuman.bsu.famcs.rag.knowledge.indexing.job.IndexingJob
import shuman.bsu.famcs.rag.knowledge.model.IndexMode
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Административные команды: статистика, низкие оценки, пробелы в знаниях, переиндексация, режим обслуживания.
 *
 * Возвращает готовый текст. Проверка, что вызывает администратор, делается в адаптере; каждый вызов пишется в `admin_audit`.
 * Админ видит запросы пользователей (они хранятся в журнале ограниченное время), но не Telegram ID.
 *
 * @property statistics выборки для отчётов.
 * @property audit журнал вызовов команд.
 * @property indexing задача индексации: запускается командой `reindex`.
 * @property maintenance переключатель режима обслуживания.
 * @property properties периоды отчётов и размеры списков.
 * @property clock часы для границ периодов и записи в журнал.
 */
class AdminServiceImpl(
    private val statistics: StatisticsRepository,
    private val audit: AdminAuditRepository,
    private val indexing: IndexingJob,
    private val maintenance: MaintenanceSwitch,
    private val properties: AdminProperties,
    private val clock: Clock,
) : AdminService {
    /** Команды, которые понимает сервис. Используются в справке при неизвестной команде. */
    private val commands: List<String> = listOf("reindex", "stats", "feedback", "gaps", "maintenance")

    override suspend fun execute(
        adminId: TelegramId,
        command: String,
        args: List<String>,
    ): String {
        audit.record(adminId, command, clock.instant())
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
     * Запускает индексацию. Без аргумента — обычное обновление (меняются только изменившиеся страницы). `full` — полная
     * пересборка в новую коллекцию: нужна после смены модели эмбеддингов или правил нарезки, которые обычное обновление не замечает.
     *
     * @param argument первый аргумент команды или `null`.
     * @return ответ администратору: запущено или уже идёт.
     */
    private fun reindex(argument: String?): String {
        val mode = if (argument.equals("full", ignoreCase = true)) IndexMode.FULL else IndexMode.INCREMENTAL
        return if (indexing.start(mode, reportSuccess = true)) {
            "Индексация ($mode) запущена. Итог придёт отдельным сообщением."
        } else {
            "Индексация уже идёт."
        }
    }

    /**
     * Включает или выключает режим обслуживания; без аргумента показывает текущее состояние.
     *
     * @param argument `on`, `off` или `null`.
     * @return ответ администратору.
     */
    private fun switchMaintenance(argument: String?): String = when (argument?.lowercase()) {
        "on" -> "Режим обслуживания включён.".also { maintenance.enabled = true }
        "off" -> "Режим обслуживания выключен.".also { maintenance.enabled = false }
        else -> "Режим обслуживания: ${if (maintenance.enabled) "включён" else "выключен"}. Команда: /admin_maintenance on|off"
    }

    /** Собирает текст статистики за период `statsWindow`: пользователи, статусы, ветки, качество, оценки. */
    private suspend fun stats(): String {
        val since = clock.instant().minus(properties.statsWindow)
        val users = statistics.userActivity(since)
        val byStatus = statistics.countByStatus(since).joinToString(", ") { "${it.name}: ${it.count}" }
        val byRoute = statistics.countByRoute(since).joinToString(", ") { "${it.name}: ${it.count}" }
        val quality = statistics.answerQuality(since)
        val rating = statistics.ratingSummary(since)
        return buildString {
            appendLine("Статистика за ${describe(properties.statsWindow)}")
            appendLine("Пользователей: ${users.total}, активных: ${users.recent}")
            appendLine("Статусы: ${byStatus.ifEmpty { "нет данных" }}")
            appendLine("Ветки: ${byRoute.ifEmpty { "нет данных" }}")
            appendLine("Ответов: ${quality.answered}, из них «не нашёл»: ${quality.noInfo}")
            appendLine("Время ответа p50 / p95: ${quality.medianLatencyMs / MS} / ${quality.p95LatencyMs / MS} с")
            appendLine("Токены вход / выход: ${quality.tokensIn} / ${quality.tokensOut}")
            append("Оценок: ${rating.count}, средняя: ${rating.average}")
        }
    }

    /** Собирает текст разбора ответов с оценкой 1–2: оценка, причина, комментарий, запрос и ответ. */
    private suspend fun lowRated(): String {
        val rows = statistics.lowRated(properties.listLimit).map { row ->
            buildString {
                append("Оценка ${row.rating}")
                row.reason?.let { append(" ($it)") }
                row.comment?.let { append(", комментарий: ${excerpt(it)}") }
                row.query?.let { append("\nЗапрос: ${excerpt(it)}") }
                row.answer?.let { append("\nОтвет: ${excerpt(it)}") }
            }
        }
        return if (rows.isEmpty()) "Низких оценок нет." else rows.joinToString("\n\n")
    }

    /** Собирает список вопросов без ответа за период `gapsWindow`: что искали и не нашли. */
    private suspend fun gaps(): String {
        val since = clock.instant().minus(properties.gapsWindow)
        val rows = statistics.knowledgeGaps(since, properties.listLimit).map { "${it.count} × ${excerpt(it.query)}" }
        return if (rows.isEmpty()) "Вопросов без ответа за ${describe(properties.gapsWindow)} нет." else rows.joinToString("\n")
    }

    /** Обрезает текст до `excerptChars` символов, чтобы список помещался в сообщение. */
    private fun excerpt(text: String) = if (text.length <= properties.excerptChars) text else text.take(properties.excerptChars) + "…"

    /** Записывает период в часах или в днях: после двух суток дни читаются легче. */
    private fun describe(window: Duration) =
        if (window.toHours() >= SWITCH_TO_DAYS_AFTER_HOURS) "${window.toDays()} дн." else "${window.toHours()} ч."

    private companion object {
        /** Перевод миллисекунд в секунды для вывода времени ответа. */
        const val MS = 1000

        /** С какого числа часов период показывается в днях. */
        const val SWITCH_TO_DAYS_AFTER_HOURS = 48
    }
}
