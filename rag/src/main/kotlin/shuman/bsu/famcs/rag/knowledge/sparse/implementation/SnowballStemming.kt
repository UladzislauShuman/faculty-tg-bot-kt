package shuman.bsu.famcs.rag.knowledge.sparse.implementation

import org.tartarus.snowball.SnowballStemmer

/** Приведение слова к основе стеммером Snowball. Общая часть английского и русского анализаторов. */
internal object SnowballStemming {
    /**
     * Возвращает основу слова [token], полученную [stemmer], или `null`, если стеммер отбросил слово целиком.
     * Стеммер хранит состояние внутри, поэтому вызывающий создаёт свой экземпляр на каждый вызов анализа.
     */
    fun stem(
        stemmer: SnowballStemmer,
        token: String,
    ): String? {
        stemmer.current = token
        stemmer.stem()
        return stemmer.current.ifEmpty { null }
    }
}
