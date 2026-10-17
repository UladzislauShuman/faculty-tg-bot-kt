package shuman.bsu.famcs.rag.knowledge.sparse.implementation

import org.apache.lucene.analysis.ru.RussianAnalyzer
import org.tartarus.snowball.SnowballStemmer
import org.tartarus.snowball.ext.RussianStemmer
import shuman.bsu.famcs.rag.knowledge.sparse.TextAnalyzer

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
                else -> SnowballStemming.stem(stemmer, token)
            }
        }
    }
}
