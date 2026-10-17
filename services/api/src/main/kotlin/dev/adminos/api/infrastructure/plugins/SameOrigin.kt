package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import java.net.URI
import java.net.URISyntaxException

/**
 * Cross-site request refusal for cookie-authenticated mutations (AOS-704, D-100; a building block for the
 * CSRF review in AOS-618). The session cookie is `SameSite=Strict`; this is the second, independent check:
 * - safe methods (GET, HEAD, OPTIONS) and requests with an `Authorization` header (scripts, which a
 *   cross-site page can't forge) pass;
 * - otherwise the browser's own `Sec-Fetch-Site` header must say `same-origin` (or `none`: typed by the
 *   user), or, when a browser doesn't send it, `Origin` must be the app's origin (`APP_URL`);
 * - anything else, including no header at all, is refused (fails closed).
 */
object SameOrigin {
    private val SAFE = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
    private val SAME_SITE_VALUES = setOf("same-origin", "none")

    fun allows(method: HttpMethod, header: (String) -> String?, appOrigin: String): Boolean = when {
        method in SAFE -> true
        !header(HttpHeaders.Authorization).isNullOrBlank() -> true
        header(SEC_FETCH_SITE) != null -> header(SEC_FETCH_SITE)?.trim()?.lowercase() in SAME_SITE_VALUES
        else -> header(HttpHeaders.Origin)?.let(::originOf) == appOrigin
    }

    /** `scheme://host[:port]`, lower-case, without a default port; null when not an http(s) origin. */
    fun originOf(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it != -1 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
        return if (port == null) "$scheme://$host" else "$scheme://$host:$port"
    }

    const val SEC_FETCH_SITE = "Sec-Fetch-Site"
}

class SameOriginConfig {
    /** The app's public URL (`APP_URL`); its origin is the only one allowed to mutate with the session cookie. */
    var appUrl: String = ""
}

/** Install on a route whose mutations use the session cookie: `install(SameOriginMutations) { appUrl = … }`. */
/** Error code for a refused cross-site mutation. */
const val CROSS_SITE_REFUSED = "AUTH_007"

val SameOriginMutations = createRouteScopedPlugin("SameOriginMutations", ::SameOriginConfig) {
    val appOrigin = SameOrigin.originOf(pluginConfig.appUrl)
        ?: throw IllegalArgumentException("APP_URL is not an http(s) URL, so cross-site requests can't be checked")
    onCall { call ->
        if (!SameOrigin.allows(call.request.httpMethod, { call.request.headers[it] }, appOrigin)) {
            throw ApiException(HttpStatusCode.Forbidden, ApiError(CROSS_SITE_REFUSED, "Cross-site request refused"))
        }
    }
}
