package shuman.bsu.famcs.rag.core

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Тексты, которые бот отправляет без участия модели (`bot.messages`).
 *
 * Зачем в конфиге: формулировки правит автор без пересборки, а ответы на отказы не стоят токенов.
 * Метки `{имя}` подставляются в момент ответа. Тексты Telegram-команд лежат в настройках адаптера.
 */
@Validated
@ConfigurationProperties("bot.messages")
data class BotMessages(
    /** В базе знаний ничего не нашлось: модель не вызывается. */
    @field:NotBlank val noInfo: String,
    /** Добавляется к каждому ответу «не нашёл», чтобы человек мог продолжить поиск сам. */
    @field:NotBlank val noInfoHint: String,
    /** Ответ на вопросы о расписании: готовых данных нет, даётся ссылка. */
    @field:NotBlank val schedule: String,
    /** Превышен минутный лимит. Метка `{seconds}` — через сколько секунд можно повторить. */
    @field:NotBlank val rateLimitedMinute: String,
    /** Превышен суточный лимит. Метка `{limit}` — сколько вопросов в сутки доступно. */
    @field:NotBlank val rateLimitedDay: String,
    /** Предыдущий вопрос того же человека ещё обрабатывается. */
    @field:NotBlank val busy: String,
    /** Очередь полна или время ответа вышло. */
    @field:NotBlank val overloaded: String,
    /** Исчерпан суточный бюджет системы. */
    @field:NotBlank val budgetExhausted: String,
    /** Внутренняя ошибка. */
    @field:NotBlank val error: String,
    /** Пользователь не дал согласия на обработку данных. */
    @field:NotBlank val consentRequired: String,
    /** Прислали не текст: фото, голос, файл. */
    @field:NotBlank val unsupportedInput: String,
    /** Слишком длинный вопрос. Метка `{limit}` — допустимая длина. */
    @field:NotBlank val tooLong: String,
    /** Бот на обслуживании. */
    @field:NotBlank val maintenance: String,
    /** Заголовок списка источников. */
    @field:NotBlank val sourcesTitle: String,
    /** Пометка даты проверки у источника. Метка `{date}` — дата в формате ДД.ММ.ГГГГ. */
    @field:NotBlank val checkedOn: String,
    /** Предупреждение к ответам, где есть даты, сроки, суммы. */
    @field:NotBlank val dateCaveat: String,
)
