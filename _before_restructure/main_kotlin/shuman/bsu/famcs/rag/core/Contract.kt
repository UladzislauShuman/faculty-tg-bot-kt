package shuman.bsu.famcs.rag.core

import shuman.bsu.famcs.rag.common.AnswerId
import shuman.bsu.famcs.rag.common.ChatId
import shuman.bsu.famcs.rag.common.TelegramId
import shuman.bsu.famcs.rag.common.UpdateId
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration

/*
 * Контракт «адаптер ↔ ядро».
 *
 * Адаптер (Telegram, HTTP для тестов) превращает входящее событие в [CoreRequest] и показывает пользователю [CoreReply].
 * Ядро ничего не знает о Telegram. Гарантия: [BotCore.ask] всегда возвращает ответ с одним из статусов [AnswerStatus],
 * исключение до пользователя не доходит.
 */

/** Запрос к ядру: кто, откуда, что написал и когда. */
data class CoreRequest(
    val userId: TelegramId,
    val chatId: ChatId,
    val updateId: UpdateId,
    /** Текст сообщения. `null` — пришло не текстовое сообщение (фото, голос, файл). */
    val text: String?,
    /** Когда Telegram получил сообщение. Нужно, чтобы отбросить слишком старые сообщения после простоя бота. */
    val sentAt: Instant,
)

/** Итог обработки. Определяет, что показать пользователю и как это учесть в метриках. */
enum class AnswerStatus {
    /** Ответ найден и сформирован. */
    ANSWERED,

    /** В базе знаний ответа нет. Пользователю — ссылка на сайт. */
    NO_INFO,

    /** Превышен лимит сообщений. */
    RATE_LIMITED,

    /** Очередь полна, исчерпан суточный потолок токенов или вышел общий таймаут сообщения. */
    OVERLOADED,

    /**
     * Сообщение не принято к обработке из-за предпроверки: нет согласия, слишком длинный текст,
     * не текст, режим обслуживания, дубль или устаревшее обновление.
     *
     * Расширение контракта из отчёта (там пять статусов): эти случаи нельзя честно назвать ни ошибкой, ни перегрузкой.
     * Причина лежит в [CoreReply.rejection].
     */
    REJECTED,

    /** Внутренняя ошибка. Пользователь видит стандартный текст. */
    ERROR,
}

/** Причина отказа при статусе [AnswerStatus.REJECTED]. */
enum class RejectionReason(
    /** Молчаливый отказ: пользователю ничего не отправляется (дубль доставки, устаревшее обновление). */
    val silent: Boolean = false,
) {
    CONSENT_REQUIRED,
    UNSUPPORTED_INPUT,
    TOO_LONG,
    MAINTENANCE,
    DUPLICATE(silent = true),
    STALE(silent = true),
}

/** Источник, на который опирается ответ. Показывается строкой «Источник: …». */
data class AnswerSource(
    val url: String,
    val title: String?,
    /** Когда страница в последний раз проверялась индексатором. */
    val checkedAt: LocalDate?,
)

/** Ответ ядра. */
data class CoreReply(
    val status: AnswerStatus,
    /** Готовый текст для пользователя. Пустой при молчаливом отказе. */
    val text: String,
    val sources: List<AnswerSource> = emptyList(),
    /** Идентификатор записи журнала. Есть только у ответов, которые можно оценить. */
    val answerId: AnswerId? = null,
    val rejection: RejectionReason? = null,
    val elapsed: Duration = Duration.ZERO,
) {
    /** Нужно ли отправлять что-то пользователю. */
    val deliverable: Boolean get() = rejection?.silent != true

    companion object {
        /** Молчаливый отказ: дубль или устаревшее обновление. */
        fun silent(reason: RejectionReason): CoreReply =
            CoreReply(status = AnswerStatus.REJECTED, text = "", rejection = reason)
    }
}

/** Вход в ядро. Единственное, что нужно адаптеру для обработки вопроса. */
fun interface BotCore {
    /** Обрабатывает сообщение пользователя. Никогда не бросает исключение (кроме отмены корутины). */
    suspend fun ask(request: CoreRequest): CoreReply
}
