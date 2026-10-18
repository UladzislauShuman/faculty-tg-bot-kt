package shuman.bsu.famcs.rag.eval

import org.yaml.snakeyaml.Yaml
import shuman.bsu.famcs.rag.eval.model.EvalCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Читает набор вопросов `eval/qa-set.yaml`.
 *
 * Формат: список страниц; у каждой `url`, одиночные `questions` (`q`, `a`) и `scenarios` (`name`, `steps` с `q`, `a`).
 * Страницы с `enabled: false` пропускаются. Правильной считается страница из `url`, приведённая к каноническому адресу по `eval/url-aliases.yaml`
 * (бот хранит страницу под каноническим адресом); когда разметка уточнится
 * (несколько страниц на вопрос), поменяется только этот загрузчик.
 */
object QaSetLoader {
    /**
     * Загружает вопросы из файла.
     *
     * @param path путь к файлу набора.
     * @return вопросы в порядке файла: сначала одиночные страницы, затем её сценарии.
     */
    fun load(path: Path): List<EvalCase> {
        val aliases = loadAliases()

        @Suppress("UNCHECKED_CAST")
        val pages = Files.newBufferedReader(path).use { Yaml().load<List<Map<String, Any?>>>(it) }
        val cases = mutableListOf<EvalCase>()
        for (page in pages) {
            if (page["enabled"] == false) continue
            val given = page.getValue("url") as String
            val url = aliases[given.trimEnd('/')] ?: given
            for (item in page.list("questions")) {
                cases += EvalCase(cases.size + 1, item.text("q"), item.text("a"), url)
            }
            for (scenario in page.list("scenarios")) {
                val name = scenario.text("name")
                scenario.list("steps").forEachIndexed { index, step ->
                    cases += EvalCase(cases.size + 1, step.text("q"), step.text("a"), url, scenario = name, step = index)
                }
            }
        }
        return cases
    }

    /** Карта «адрес-алиас → канонический адрес» из `eval/url-aliases.yaml`; пустая, если файла нет. */
    private fun loadAliases(): Map<String, String> {
        val file = Path.of(System.getProperty("eval.aliases", "eval/url-aliases.yaml"))
        if (!Files.exists(file)) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val map = Files.newBufferedReader(file).use { Yaml().load<Map<String, String>?>(it) }.orEmpty()
        return map
    }

    /** Список вложенных записей по ключу; пустой, если ключа нет. */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> = (this[key] as? List<Map<String, Any?>>).orEmpty()

    /** Текстовое поле записи; числа и даты в YAML приводятся к строке. */
    private fun Map<String, Any?>.text(key: String): String = this.getValue(key).toString().trim()
}
