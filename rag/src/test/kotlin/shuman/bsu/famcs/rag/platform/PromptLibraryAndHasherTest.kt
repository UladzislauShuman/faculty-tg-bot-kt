package shuman.bsu.famcs.rag.platform

import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.platform.identifier.TelegramId
import shuman.bsu.famcs.rag.platform.privacy.UserHasher
import shuman.bsu.famcs.rag.platform.text.PromptLibrary
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PromptLibraryTest {
    private val library = PromptLibrary(loader = { path ->
        mapOf("/prompts/base/greeting.txt" to "Привет, {name}! Вопрос: {input}\n\n")[path]
    })

    /** Профиль `v2`, в котором переопределён только `greeting`; `farewell` есть лишь в `base`. */
    private val files = mapOf(
        "/prompts/base/greeting.txt" to "base: {name}",
        "/prompts/base/farewell.txt" to "пока",
        "/prompts/v2/greeting.txt" to "v2: {name}",
    )

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
    fun `профиль переопределяет только свои файлы, остальные берутся из base`() {
        val v2 = PromptLibrary("v2", files::get)
        val base = PromptLibrary(loader = files::get)

        assertEquals("v2: Аня", v2.render("greeting", "name" to "Аня"))
        assertEquals("пока", v2.render("farewell"))
        assertEquals("base: Аня", base.render("greeting", "name" to "Аня"))
        assertEquals("v2", v2.sourceOf("greeting"))
        assertEquals("base", v2.sourceOf("farewell"))
    }

    @Test
    fun `промпт, которого нет ни в профиле, ни в base, — ошибка с именем файла`() {
        val error = kotlin.runCatching { PromptLibrary("v2", files::get).render("missing") }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("prompts/base/missing.txt"))
    }

    @Test
    fun `в каждом профиле ответ содержит фразу «не нашёл» и метки, иначе бот не запустится`() {
        for (profile in listOf("base", "v2")) {
            val library = PromptLibrary(profile)
            val system = library.render("answer-system").lowercase()

            assertTrue("в моей базе знаний пока нет точной информации" in system, "профиль $profile")
            assertTrue("{context}" in system, "профиль $profile: место для разделов")
            assertTrue("{input}" in library.render("answer-human"), "профиль $profile: место для вопроса")
        }
        assertEquals("v2", PromptLibrary("v2").sourceOf("rewrite-system"))
        assertEquals("base", PromptLibrary("v2").sourceOf("judge-correctness"), "судья в v2 не меняется: сравнение идёт одним судьёй")
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
