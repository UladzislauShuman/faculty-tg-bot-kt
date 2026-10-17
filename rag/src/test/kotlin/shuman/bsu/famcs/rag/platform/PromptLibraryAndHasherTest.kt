package shuman.bsu.famcs.rag.platform

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PromptLibraryTest {
    private val library = PromptLibrary(loader = { name ->
        mapOf("greeting" to "Привет, {name}! Вопрос: {input}\n\n")[name]
            ?: error("нет $name")
    })

    @Test
    fun `значения подставляются на место меток, хвостовые переводы строк убираются`() {
        assertEquals("Привет, Аня! Вопрос: сколько?", library.render("greeting", "name" to "Аня", "input" to "сколько?"))
    }

    @Test
    fun `метка внутри подставленного значения повторно не подменяется`() {
        val rendered = library.render("greeting", "name" to "{input}", "input" to "вопрос")

        assertEquals("Привет, {input}! Вопрос: вопрос", rendered)
    }

    @Test
    fun `неизвестная метка остаётся как есть`() {
        assertEquals("Привет, {name}! Вопрос: x", library.render("greeting", "input" to "x"))
    }

    @Test
    fun `промпты из ресурсов читаются и содержат свои метки`() {
        val real = PromptLibrary()

        assertTrue("{context}" in real.render("answer-system"))
        assertTrue("Диалог:\nтекст" in real.render("summary", "dialogue" to "текст", "max_chars" to "300"))
        assertTrue("Сообщение: привет" in real.render("classifier-human", "query" to "привет"))
    }
}

class UserHasherTest {
    private val hasher = UserHasher(secret = "test-secret-0123456789", length = 16)

    @Test
    fun `один и тот же пользователь даёт один и тот же хеш`() {
        assertEquals(hasher.hash(TelegramId(1)), hasher.hash(TelegramId(1)))
        assertEquals(16, hasher.hash(TelegramId(1)).value.length)
    }

    @Test
    fun `разные пользователи и разные секреты дают разные хеши`() {
        assertNotEquals(hasher.hash(TelegramId(1)), hasher.hash(TelegramId(2)))
        assertNotEquals(hasher.hash(TelegramId(1)), UserHasher("other-secret-0123456789", 16).hash(TelegramId(1)))
    }

    @Test
    fun `в хеше нет исходного идентификатора`() {
        assertTrue("123456789" !in hasher.hash(TelegramId(123456789)).value)
    }
}
