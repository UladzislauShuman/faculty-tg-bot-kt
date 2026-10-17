package shuman.bsu.famcs.rag.testsupport

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Доступ к эталонным данным Python-версии (src/test/resources/parity).
 * Файлы создаёт scripts/generate_python_reference.py; тесты паритета сверяют с ними Kotlin-реализации.
 */
object Parity {
    private val mapper = JsonMapper.builder().build()

    fun text(name: String): String =
        requireNotNull(Parity::class.java.getResourceAsStream("/parity/$name")) { "Нет файла parity/$name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun json(name: String): JsonNode = mapper.readTree(text(name))
}
