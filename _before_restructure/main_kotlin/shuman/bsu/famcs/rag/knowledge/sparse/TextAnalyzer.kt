package shuman.bsu.famcs.rag.knowledge.sparse

import org.apache.lucene.analysis.ru.RussianAnalyzer
import org.tartarus.snowball.SnowballStemmer
import org.tartarus.snowball.ext.EnglishStemmer
import org.tartarus.snowball.ext.RussianStemmer
import java.util.Locale

/**
 * Превращает текст в список термов для BM25: слова в нижнем регистре, без стоп-слов, приведённые к основе.
 *
 * Анализатор — единственное, что отличает Kotlin-кодировщик от Python-baseline. Один и тот же анализатор обязан
 * использоваться при индексации и при поиске, иначе слова из запроса и из документа не совпадут.
 */
fun interface TextAnalyzer {
    fun analyze(text: String): List<String>
}

/** Делит текст на слова: максимальные последовательности букв, цифр и подчёркиваний. Регистр понижается. */
internal object WordTokenizer {
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        text.lowercase(Locale.ROOT).codePoints().forEach { codePoint ->
            if (isWordCodePoint(codePoint)) {
                current.appendCodePoint(codePoint)
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** Совпадает с `\w` Python: буквы, цифры любых алфавитов и подчёркивание. */
    private fun isWordCodePoint(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) ||
            codePoint == '_'.code ||
            Character.getType(codePoint).let { it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt() }
}

/**
 * Анализатор, воспроизводящий `Qdrant/bm25` из FastEmbed (Python) с настройками по умолчанию, как в baseline.
 *
 * Особенность baseline: язык не задавался, поэтому используется английский: английские стоп-слова и английский стеммер.
 * К русским словам он почти ничего не применяет, и поиск по ключевым словам идёт по неизменённым словоформам.
 * Совпадение с Python проверяется тестом паритета (`parity/sparse_bm25.json`).
 */
class FastEmbedCompatibleAnalyzer(
    private val stopWords: Set<String>,
    private val maxTokenLength: Int,
) : TextAnalyzer {
    override fun analyze(text: String): List<String> {
        // Стеммер хранит состояние внутри, поэтому на каждый вызов создаётся свой экземпляр: так анализатор безопасен для потоков.
        val stemmer = EnglishStemmer()
        return WordTokenizer.tokenize(text).mapNotNull { token ->
            when {
                token == PUNCTUATION_TOKEN -> null
                token in stopWords -> null
                token.length > maxTokenLength -> null
                else -> stem(stemmer, token)
            }
        }
    }

    private companion object {
        /** Единственный «знак препинания», который токенизатор может выдать как слово. */
        const val PUNCTUATION_TOKEN = "_"
    }
}

/**
 * Русский анализатор: русские стоп-слова и русский стеммер Snowball.
 *
 * Не входит в паритет с baseline: включается настройкой как отдельный эксперимент. Ожидание: «документы», «документов»,
 * «документам» сходятся к одной основе, и ключевой поиск находит больше релевантного.
 */
class RussianTextAnalyzer(
    private val maxTokenLength: Int,
) : TextAnalyzer {
    private val stopWords: Set<String> = RussianAnalyzer.getDefaultStopSet().map { String(it as CharArray) }.toSet()

    override fun analyze(text: String): List<String> {
        val stemmer = RussianStemmer()
        return WordTokenizer.tokenize(text).mapNotNull { token ->
            when {
                token in stopWords || token.length > maxTokenLength -> null
                else -> stem(stemmer, token)
            }
        }
    }
}

private fun stem(
    stemmer: SnowballStemmer,
    token: String,
): String? {
    stemmer.current = token
    stemmer.stem()
    return stemmer.current.ifEmpty { null }
}
