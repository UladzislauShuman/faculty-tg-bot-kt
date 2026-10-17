package shuman.bsu.famcs.rag.telegram

import org.junit.jupiter.api.Test
import java.net.http.HttpTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenRedactionTest {
    private val token = "123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"

    @Test
    fun `токен в адресе запроса вырезается`() {
        val text = "Connect timeout has expired [url=https://api.telegram.org/bot$token/getUpdates, connect_timeout=10000 ms]"

        val redacted = TokenRedaction.redact(text)

        assertFalse(token in redacted)
        assertFalse("123456789" in redacted)
        assertTrue("https://api.telegram.org/***/getUpdates" in redacted)
    }

    @Test
    fun `токен без префикса bot тоже вырезается`() {
        assertEquals("токен *** в тексте", TokenRedaction.redact("токен $token в тексте"))
    }

    @Test
    fun `обычный текст и пустое значение не меняются`() {
        assertEquals("время 12:30 в 2026", TokenRedaction.redact("время 12:30 в 2026"))
        assertEquals("", TokenRedaction.redact(null))
    }

    @Test
    fun `описание ошибки содержит класс и очищенный текст, но не токен`() {
        val error = HttpTimeoutException("timeout https://api.telegram.org/bot$token/sendMessage")

        val description = TokenRedaction.describe(error)

        assertTrue(description.startsWith("HttpTimeoutException: timeout"))
        assertFalse(token in description)
    }
}
