package shuman.bsu.famcs.rag.quality.judge.service

import shuman.bsu.famcs.rag.quality.judge.model.FactCheck
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Точные факты эталона и их поиск в ответе без модели: телефоны, ссылки, почта, числа (годы, кабинеты, баллы), фамилии и
 * названия с заглавной буквы. Фамилии сравниваются по основе (без окончания), чтобы «Орлович» нашёлся в «Орловича».
 * Русские аббревиатуры прописными (ФПМИ, НИИ ППМИ) из текста эталона не берутся: ответ часто пишет их полностью.
 * Числа сравниваются без ведущих нулей («02 декабря» = «2 декабря»).
 * Если у вопроса заданы ключевые факты списком, проверяются они, а не извлечённые из текста.
 */
object ReferenceFacts {
    private val URL = Regex("https?://[^\\s)\\]>\"']+")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
    private val PHONE = Regex("(?:\\+?375[\\s(-]*\\d{2}[\\s)-]*)?\\d{3}[-\\s]\\d{2}[-\\s]\\d{2}")
    private val NUMBER = Regex("(?<![\\d.])\\d{2,}(?![\\d])")
    private val CAPITALIZED = Regex("(?<![\\p{L}])[A-ZА-ЯЁ][\\p{L}-]{3,}")
    private val SENTENCE_START = Regex("(^|[.!?:]\\s+|\\n\\s*)$")
    private val WORD = Regex("[\\p{L}\\d]+")
    private val LEADING_ZEROS = Regex("(?<!\\d)0+(?=\\d)")
    private const val PHONE_DIGITS = 7
    private const val MIN_TOKEN = 4

    /**
     * Факты для проверки.
     *
     * @param reference текст эталона.
     * @param explicit ключевые факты, заданные в наборе вопросов; если не пусты, используются они.
     */
    fun extract(
        reference: String,
        explicit: List<String> = emptyList(),
    ): List<String> {
        if (explicit.isNotEmpty()) return explicit
        val facts = linkedSetOf<String>()
        var rest = reference
        for (regex in listOf(URL, EMAIL, PHONE)) {
            regex.findAll(rest).forEach { facts += it.value.trimEnd('.', ',', ';') }
            rest = regex.replace(rest, " ")
        }
        NUMBER.findAll(rest).forEach { facts += it.value.trimStart('0').ifEmpty { "0" } }
        CAPITALIZED.findAll(rest).forEach { match ->
            val abbreviation = match.value.all { it.isUpperCase() || it == '-' } && match.value.any { it in 'А'..'Я' || it == 'Ё' }
            if (!abbreviation && !SENTENCE_START.containsMatchIn(rest.substring(0, match.range.first))) facts += match.value
        }
        return facts.toList()
    }

    /**
     * Ищет факты в ответе.
     *
     * @param facts факты ([extract]).
     * @param answer ответ бота.
     */
    fun check(
        facts: List<String>,
        answer: String,
    ): FactCheck {
        val text = LEADING_ZEROS.replace(normalize(answer), "")
        val digits = answer.filter(Char::isDigit)
        return FactCheck(facts, facts.filterNot { found(it, text, digits) })
    }

    /** Есть ли факт в ответе: телефон по последним семи цифрам, ссылка и почта по тексту, остальное по основам слов. */
    private fun found(
        fact: String,
        text: String,
        digits: String,
    ): Boolean = when {
        URL.matches(fact) -> {
            normalize(fact).trimEnd('/').substringAfterLast('/').let { it.isNotEmpty() && it in text }
        }

        EMAIL.matches(fact) -> {
            normalize(fact) in text
        }

        PHONE.matches(fact) -> {
            fact.filter(Char::isDigit).takeLast(PHONE_DIGITS) in digits
        }

        else -> {
            WORD
                .findAll(normalize(fact))
                .map { it.value }
                .filter { it.length >= MIN_TOKEN || it.all(Char::isDigit) }
                .toList()
                .ifEmpty { listOf(normalize(fact)) }
                .all { token ->
                    if (token.all(Char::isDigit)) Regex("(?<!\\d)$token(?!\\d)").containsMatchIn(text) else stem(token) in text
                }
        }
    }

    /** Основа слова, чтобы совпадали падежи: у слов длиннее пяти букв без двух последних, у пятибуквенных без одной. */
    private fun stem(word: String): String = when {
        word.length > MIN_TOKEN + 1 -> word.dropLast(2)
        word.length == MIN_TOKEN + 1 -> word.dropLast(1)
        else -> word
    }

    /** Нижний регистр, «ё» как «е», процентные последовательности ссылок раскодированы. */
    private fun normalize(text: String): String {
        val decoded = runCatching { URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8) }.getOrDefault(text)
        return decoded.lowercase().replace('ё', 'е')
    }
}
