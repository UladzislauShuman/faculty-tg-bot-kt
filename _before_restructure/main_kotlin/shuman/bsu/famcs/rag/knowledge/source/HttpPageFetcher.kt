package shuman.bsu.famcs.rag.knowledge.source

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shuman.bsu.famcs.rag.knowledge.FetchedDocument
import shuman.bsu.famcs.rag.knowledge.KnowledgeProperties
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * Скачивает страницы по HTTP вежливо и устойчиво.
 *
 * Вежливо: между запросами к сайту выдерживается пауза (не чаще заданной частоты), сайту сообщается, кто его читает.
 * Устойчиво: временные сбои (сеть, 5xx, 429) повторяются с нарастающей паузой; окончательный сбой не роняет обход.
 */
class HttpPageFetcher(
    private val client: HttpClient,
    private val settings: KnowledgeProperties.Crawler,
    private val clock: Clock,
) : PageFetcher {
    private val pacing = Mutex()
    private var lastRequestAt = TimeSource.Monotonic.markNow() - settings.delayBetweenRequests.toKotlinDuration()

    override suspend fun fetch(url: String): FetchResult {
        var lastReason = "нет ответа"
        for (attempt in 0..settings.maxRetries) {
            if (attempt > 0) delay(settings.retryBackoff.toKotlinDuration() * attempt)
            awaitTurn()
            try {
                val response = client.sendAsync(request(url), HttpResponse.BodyHandlers.ofString()).await()
                val status = response.statusCode()
                when {
                    status == HTTP_NOT_FOUND || status == HTTP_GONE -> return FetchResult.NotFound(url, status)
                    status in HTTP_OK_RANGE -> return toResult(url, response)
                    status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR -> lastReason = "HTTP $status"
                    else -> return FetchResult.Failed(url, "HTTP $status")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastReason = "${e.javaClass.simpleName}: ${e.message}"
            }
            log.debug { "Попытка ${attempt + 1} для $url не удалась: $lastReason" }
        }
        return FetchResult.Failed(url, lastReason)
    }

    /** Выдерживает паузу между запросами. Очередь сериализована: запросы идут по одному. */
    private suspend fun awaitTurn() = pacing.withLock {
        val wait = settings.delayBetweenRequests.toKotlinDuration() - lastRequestAt.elapsedNow()
        if (wait > 0.milliseconds) delay(wait)
        lastRequestAt = TimeSource.Monotonic.markNow()
    }

    private fun request(url: String): HttpRequest = HttpRequest
        .newBuilder(URI.create(url))
        .GET()
        .timeout(settings.requestTimeout)
        .header("User-Agent", settings.userAgent)
        .header("Accept", "text/html,application/xhtml+xml")
        .build()

    private fun toResult(
        url: String,
        response: HttpResponse<String>,
    ): FetchResult {
        val mediaType = response
            .headers()
            .firstValue("Content-Type")
            .orElse("text/html")
            .substringBefore(';')
            .trim()
            .lowercase()
        // Текстовые ответы (страницы, robots.txt) возвращаются как есть; двоичные файлы в индекс не идут.
        if (!mediaType.startsWith("text/")) return FetchResult.Unsupported(url, mediaType)
        return FetchResult.Success(FetchedDocument(url = url, mediaType = mediaType, body = response.body(), fetchedAt = clock.instant()))
    }

    private companion object {
        val HTTP_OK_RANGE = 200..299
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
