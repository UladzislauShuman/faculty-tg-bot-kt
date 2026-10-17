package shuman.bsu.famcs.rag.knowledge.model

/** Запрос гибридного поиска: вектор по смыслу и вектор по ключевым словам. */
class HybridSearchRequest(
    val dense: FloatArray,
    val sparse: SparseVector,
)
