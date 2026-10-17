package shuman.bsu.famcs.rag.knowledge.sparse.implementation

import org.tartarus.snowball.ext.EnglishStemmer
import shuman.bsu.famcs.rag.knowledge.sparse.TextAnalyzer

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
                else -> SnowballStemming.stem(stemmer, token)
            }
        }
    }

    private companion object {
        /** Единственный «знак препинания», который токенизатор может выдать как слово. */
        const val PUNCTUATION_TOKEN = "_"
    }
}
