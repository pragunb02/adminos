package dev.adminos.api.infrastructure.prices

import dev.adminos.api.domain.wealth.prices.NavFileSource
import dev.adminos.api.domain.wealth.prices.PriceFetchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Downloads AMFI's daily NAV file (AOS-810): one GET of a public whole-market file, so nothing about the holdings is
 * sent (D-79). HTTPS only, no redirects followed (a moved file fails loudly instead of fetching from somewhere else),
 * a size cap, and a timeout. No credentials, cookies or identifying headers are sent.
 */
class HttpAmfiNavSource(
    url: String = DEFAULT_URL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
    private val maxBytes: Int = MAX_BYTES
) : NavFileSource {
    private val uri: URI = URI.create(url).also {
        require(it.scheme == "https" || it.host == "127.0.0.1") { "the AMFI NAV URL must be https" }
    }

    override suspend fun download(): String {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("Accept", "text/plain").GET().build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (e: IOException) {
            throw PriceFetchException("The AMFI NAV file couldn't be fetched (${e.javaClass.simpleName})")
        }
        response.body().use { body ->
            if (response.statusCode() != 200) throw PriceFetchException("The AMFI NAV file answered ${response.statusCode()}")
            return withContext(Dispatchers.IO) { readCapped(body) }
        }
    }

    private fun readCapped(body: InputStream): String {
        val bytes = body.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw PriceFetchException("The AMFI NAV file is larger than expected")
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        /** Checked 2026-10-18: served from AMFI's portal (the old www address redirects here). */
        const val DEFAULT_URL = "https://portal.amfiindia.com/spages/NAVAll.txt"
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
