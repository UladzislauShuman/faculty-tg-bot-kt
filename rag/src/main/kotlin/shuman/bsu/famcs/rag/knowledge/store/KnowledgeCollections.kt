package shuman.bsu.famcs.rag.knowledge.store

/** Жизненный цикл коллекций: создание, переключение активной, удаление старых. */
interface KnowledgeCollections {
    /** Физическая коллекция, на которую сейчас указывает алиас. `null`, если алиаса нет. */
    suspend fun active(): String?

    /** Создаёт пустую коллекцию со схемой и возвращает её имя. */
    suspend fun create(): String

    /** Гарантирует, что активная коллекция есть: при первом запуске создаёт пустую и направляет на неё алиас. */
    suspend fun ensureActive(): String

    /** Атомарно переключает алиас на [collection]: поиск мгновенно видит новую коллекцию. */
    suspend fun activate(collection: String)

    /** Удаляет старые коллекции, оставляя активную и несколько последних для отката. */
    suspend fun dropOld()

    /** Удаляет [collection], которую не удалось довести до рабочей (прерванная пересборка). Активную коллекцию не удаляет. */
    suspend fun discard(collection: String)
}
