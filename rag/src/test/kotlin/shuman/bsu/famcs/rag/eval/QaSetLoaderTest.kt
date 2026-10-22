package shuman.bsu.famcs.rag.eval

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** Чтение набора вопросов: одиночные вопросы, шаги сценария и необязательные ключевые факты. */
class QaSetLoaderTest {
    @Test
    fun `ключевые факты читаются у вопросов и шагов, без поля список пуст`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("qa.yaml")
        Files.writeString(
            file,
            """
            - url: "https://site.test/master"
              questions:
                - q: "На каких языках учиться?"
                  a: "На русском и английском."
                  facts: ["русском", "английском"]
                - q: "Сколько лет?"
                  a: "2 года."
              scenarios:
                - name: "Экзамен"
                  steps:
                    - q: "Как проходит экзамен?"
                      a: "Устно."
                      facts: ["устной"]
            """.trimIndent(),
        )

        val cases = QaSetLoader.load(file)

        assertEquals(listOf(listOf("русском", "английском"), emptyList(), listOf("устной")), cases.map { it.facts })
        assertEquals("Экзамен", cases.last().scenario)
    }
}
