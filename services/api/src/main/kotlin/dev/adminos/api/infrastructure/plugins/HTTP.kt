package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.identity.LOGIN_STATE_COOKIE
import dev.adminos.api.domain.identity.REFRESH_TOKEN_COOKIE
import dev.adminos.api.domain.identity.isLocalDevUrl
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.net.URI

/**
 * Browser-facing HTTP hardening for the API (AOS-618):
 * - [SecurityHeaders] on every response (AUD-07);
 * - [OriginCheck] on every cookie-authenticated mutation (AUD-08).
 *
 * No CORS (AUD-17): the browser only ever talks to the web origin, which proxies the API paths (D-32), so a
 * cross-origin browser call to the API is never legitimate and gets no `Access-Control-*` headers.
 */
fun Application.configureHTTP(appUrl: String) {
    install(SecurityHeaders) { hsts = !isLocalDevUrl(appUrl) }
    install(OriginCheck) { allowedOrigin = originOf(appUrl) }
}

/** `scheme://host[:port]` of [url], lower-case, the way browsers send `Origin`. */
internal fun originOf(url: String): String {
    val uri = URI(url.trim())
    val scheme = requireNotNull(uri.scheme) { "APP_URL needs a scheme: $url" }.lowercase()
    val host = requireNotNull(uri.host) { "APP_URL needs a host: $url" }.lowercase()
    val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) "" else ":${uri.port}"
    return "$scheme://$host$port"
}

class SecurityHeadersConfig {
    /** HSTS only when the app is served over HTTPS (it is ignored over http, and would pin localhost). */
    var hsts: Boolean = true
}

/**
 * API responses are JSON (or a downloaded file), never a page: nothing may frame, sniff, cache, embed or
 * script them. Route handlers may still set `Content-Disposition` and their own `Content-Type`.
 */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders", ::SecurityHeadersConfig) {
    val hsts = pluginConfig.hsts
    onCall { call ->
        val h = call.response.headers
        h.append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'", safeOnly = false)
        h.append("X-Content-Type-Options", "nosniff", safeOnly = false)
        h.append("X-Frame-Options", "DENY", safeOnly = false)
        h.append("Referrer-Policy", "no-referrer", safeOnly = false)
        h.append("Cross-Origin-Resource-Policy", "same-origin", safeOnly = false)
        h.append("Cross-Origin-Opener-Policy", "same-origin", safeOnly = false)
        h.append("Permissions-Policy", PERMISSIONS_POLICY, safeOnly = false)
        h.append(HttpHeaders.CacheControl, "no-store", safeOnly = false)
        if (hsts) h.append(HttpHeaders.StrictTransportSecurity, "max-age=63072000; includeSubDomains", safeOnly = false)
    }
}

/** Every powerful browser feature off; the app needs none of them. */
const val PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), " +
    "payment=(), usb=(), interest-cohort=(), browsing-topics=()"

class OriginCheckConfig {
    var allowedOrigin: String = ""
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * CSRF defence in depth (AOS-618, AUD-08), on top of `SameSite=Strict`:
 * a state-changing request that carries a session cookie must prove it comes from our own pages.
 * - `Sec-Fetch-Site: same-origin` (Fetch Metadata) passes; any other value (`same-site`, `cross-site`, `none`) is refused.
 * - Otherwise `Origin` must equal the app's origin.
 * - Neither header → refused (fail closed): every browser we support sends at least one on a POST/PATCH/DELETE.
 * Requests without session cookies (device tokens in the header, the Pub/Sub webhook) can't be forged by a
 * browser and are not checked.
 */
val OriginCheck = createApplicationPlugin("OriginCheck", ::OriginCheckConfig) {
    val allowed = pluginConfig.allowedOrigin.lowercase()
    onCall { call ->
        val request = call.request
        if (request.httpMethod in SAFE_METHODS) return@onCall
        val cookies = request.cookies
        if (SESSION_COOKIES.none { cookies[it] != null }) return@onCall
        val fetchSite = request.headers["Sec-Fetch-Site"]?.trim()?.lowercase()
        val origin = request.headers[HttpHeaders.Origin]?.trim()?.lowercase()
        val ok = when {
            fetchSite != null -> fetchSite == "same-origin" && (origin == null || origin == allowed)
            origin != null -> origin == allowed
            else -> false
        }
        if (!ok) call.respond(HttpStatusCode.Forbidden, ApiResponse.error(CROSS_SITE_REFUSED, call.requestId))
    }
}

/** Cookies that make a request act as the owner (or finish their sign-in). */
private val SESSION_COOKIES = listOf(ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE, LOGIN_STATE_COOKIE)

val CROSS_SITE_REFUSED = ApiError("CSRF_001", "This request didn't come from AdminOS")
