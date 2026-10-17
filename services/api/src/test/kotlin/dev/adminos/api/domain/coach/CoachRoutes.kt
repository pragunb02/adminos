package dev.adminos.api.domain.coach

import dev.adminos.api.config.AppConfig
import io.ktor.client.request.*
import io.ktor.http.*

/**
 * Coach route tests send what the web app sends: the session cookie and, on every mutation, the app's own
 * Origin (so they keep passing once the API checks Origin on cookie mutations, AOS-618).
 */
object CoachRoutes {
    val APP_ORIGIN: String = AppConfig.load().app.appUrl.trimEnd('/')

    fun HttpRequestBuilder.asTheApp() {
        if (method != HttpMethod.Get) header(HttpHeaders.Origin, APP_ORIGIN)
    }
}
