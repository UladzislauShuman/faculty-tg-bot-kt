package shuman.bsu.famcs.rag.assistant.limit.service

import io.github.oshai.kotlinlogging.KotlinLogging
import shuman.bsu.famcs.rag.assistant.configuration.properties.LimitProperties
import shuman.bsu.famcs.rag.assistant.limit.model.BudgetMode
import shuman.bsu.famcs.rag.assistant.limit.repository.UsageRepository
import shuman.bsu.famcs.rag.llm.model.LlmRole
import shuman.bsu.famcs.rag.llm.model.TokenUsage
import shuman.bsu.famcs.rag.llm.service.LlmUsageListener
import shuman.bsu.famcs.rag.platform.notification.AdminNotifier
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Предохранитель по деньгам: следит за суточным расходом токенов и вопросов всей системы.
 *
 * Один спамер или всплеск без предохранителя тратит месячный бюджет за часы. Предохранитель переводит бота в режим экономии,
 * затем в отказ и заранее предупреждает администратора. Он же получает расход каждого вызова модели ([LlmUsageListener]).
 */
class BudgetGuard(
    private val usage: UsageRepository,
    private val limits: LimitProperties.System,
    private val clock: Clock,
    private val notifier: AdminNotifier,
) : LlmUsageListener {
    /** Уже отправленное сегодня предупреждение: сутки и уровень (1 — порог предупреждения, 2 — потолок). Не даёт слать одно и то же. */
    private val alerted = AtomicReference(LocalDate.MIN to 0)

    /** Режим на текущий момент. Читает счётчики из базы: один лёгкий запрос на вопрос. */
    suspend fun mode(): BudgetMode {
        val today = usage.system(LocalDate.now(clock))
        return when {
            today.tokens >= limits.tokensPerDay || today.messages >= limits.messagesPerDay -> BudgetMode.EXHAUSTED
            today.tokens * PERCENT >= limits.tokensPerDay * limits.economyThresholdPercent -> BudgetMode.ECONOMY
            else -> BudgetMode.NORMAL
        }
    }

    override suspend fun onUsage(
        role: LlmRole,
        provider: String,
        model: String,
        usage: TokenUsage,
    ) {
        val day = LocalDate.now(clock)
        val total = this.usage.addSystemTokens(day, usage.totalTokens.toLong())
        val level = when {
            total >= limits.tokensPerDay -> LEVEL_CEILING
            total * PERCENT >= limits.tokensPerDay * limits.alertThresholdPercent -> LEVEL_WARNING
            else -> 0
        }
        if (level > 0 && raiseAlertLevel(day, level)) {
            val percent = total * PERCENT / limits.tokensPerDay
            log.warn { "Расход токенов за сутки: $total из ${limits.tokensPerDay} ($percent%)" }
            notifier.notifyAdmins(
                if (level == LEVEL_CEILING) {
                    "Суточный потолок токенов исчерпан ($total из ${limits.tokensPerDay}). Бот отвечает «перегрузка» до полуночи."
                } else {
                    "Расход токенов за сутки: $percent% потолка ($total из ${limits.tokensPerDay})."
                },
            )
        }
    }

    /** Поднимает уровень оповещения за [day]. `true`, если этот уровень сегодня ещё не отправлялся. */
    private fun raiseAlertLevel(
        day: LocalDate,
        level: Int,
    ): Boolean {
        while (true) {
            val current = alerted.get()
            val sameDay = current.first == day
            if (sameDay && current.second >= level) return false
            if (alerted.compareAndSet(current, day to level)) return true
        }
    }

    private companion object {
        const val PERCENT = 100L
        const val LEVEL_WARNING = 1
        const val LEVEL_CEILING = 2
    }
}
