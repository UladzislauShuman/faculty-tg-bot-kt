package shuman.bsu.famcs.rag.ui.telegram.gateway

import dev.inmo.tgbotapi.bot.exceptions.CommonRequestException
import dev.inmo.tgbotapi.types.Response
import io.mockk.mockk
import org.junit.jupiter.api.Test
import shuman.bsu.famcs.rag.ui.telegram.exception.TelegramSendException
import shuman.bsu.famcs.rag.ui.telegram.exception.UserBlockedBotException
import shuman.bsu.famcs.rag.ui.telegram.gateway.implementation.KtgbotapiGateway
import java.net.http.HttpTimeoutException
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtgbotapiGatewayTest {
    private val gateway = KtgbotapiGateway(bot = mockk(relaxed = true), retries = 2)
    private val token = "123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"

    private fun apiError(
        code: Int,
        description: String,
    ) = CommonRequestException(Response(false, description, code, null, null), description, description, null)

    @Test
    fun `код 403 превращается в блокировку бота пользователем`() {
        val error = gateway.translate(apiError(403, "Forbidden: bot was blocked by the user"))

        assertIs<UserBlockedBotException>(error)
    }

    @Test
    fun `403 по другой причине, например удалённый аккаунт, тоже считается недоставкой`() {
        assertIs<UserBlockedBotException>(gateway.translate(apiError(403, "Forbidden: user is deactivated")))
    }

    @Test
    fun `прочие ошибки превращаются в ошибку отправки без токена и без исходного исключения`() {
        val original = HttpTimeoutException("Connect timeout has expired [url=https://api.telegram.org/bot$token/sendMessage]")

        val error = gateway.translate(original)

        assertIs<TelegramSendException>(error)
        assertFalse(token in error.message.orEmpty(), "в сообщении нет токена")
        assertTrue(error.cause == null, "исходное исключение с адресом не передаётся дальше")
    }
}
