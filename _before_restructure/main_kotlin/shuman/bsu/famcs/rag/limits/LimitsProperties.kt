package shuman.bsu.famcs.rag.limits

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Лимиты и защита бюджета (`bot.limits`).
 *
 * Зачем: один спамер или всплеск нагрузки не должен израсходовать месячный бюджет за час. Лимиты на пользователя защищают
 * от злоупотребления, лимиты системы — от перерасхода, очередь — от перегрузки процесса.
 */
@Validated
@ConfigurationProperties("bot.limits")
data class LimitsProperties(
    @field:Valid val user: User,
    @field:Valid val system: System,
    @field:Valid val queue: Queue,
    @field:Valid val updates: Updates,
    @field:Valid val input: Input,
) {
    data class Input(
        /** Самый длинный вопрос, символы. Длинный ввод дорог в токенах и часто бывает вставкой не того текста. */
        @field:Positive val maxQuestionChars: Int,
    )

    data class User(
        /** Сколько вопросов можно задать за скользящую минуту. */
        @field:Positive val perMinute: Int,
        /** Сколько вопросов в сутки (до полуночи по часовому поясу бота). */
        @field:Positive val perDay: Int,
        /** Суточный лимит в режиме экономии, когда расход токенов близок к потолку. Меньше обычного. */
        @field:Positive val perDayInEconomy: Int,
    )

    data class System(
        /** Потолок вопросов всех пользователей в сутки. После него бот отвечает «перегрузка». */
        @field:Positive val messagesPerDay: Int,
        /** Потолок токенов всех вызовов моделей в сутки: предохранитель по деньгам. */
        @field:Positive val tokensPerDay: Long,
        /** С какой доли суточного потолка токенов включается режим экономии, проценты. */
        @field:Min(1) @field:Max(100) val economyThresholdPercent: Int,
        /** С какой доли потолка токенов администратору уходит предупреждение, проценты. */
        @field:Min(1) @field:Max(100) val alertThresholdPercent: Int,
    )

    data class Queue(
        /** Сколько вопросов обрабатывается одновременно. Согласуется с квотами провайдеров (Yandex — 10, GigaChat физлица — 1 поток). */
        @field:Positive val maxConcurrent: Int,
        /** Сколько вопросов может ждать своей очереди. Остальные получают «перегрузка» сразу. */
        @field:Min(0) val maxWaiting: Int,
        /** Сколько вопрос может ждать очереди. */
        val maxWait: Duration,
        /** Потолок обработки одного вопроса с момента, как он занял место. По истечении — «перегрузка». */
        val processingTimeout: Duration,
    )

    data class Updates(
        /** Обновления старше этого возраста после простоя бота молча пропускаются: отвечать на вчерашние вопросы поздно. */
        val maxAge: Duration,
        /** Как долго хранятся номера обработанных обновлений. Дольше окна повторной доставки Telegram. */
        val retention: Duration,
    )
}
