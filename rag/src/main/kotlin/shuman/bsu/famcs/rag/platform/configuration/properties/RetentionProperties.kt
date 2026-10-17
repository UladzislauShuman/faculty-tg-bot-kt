package shuman.bsu.famcs.rag.platform.configuration.properties

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

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
