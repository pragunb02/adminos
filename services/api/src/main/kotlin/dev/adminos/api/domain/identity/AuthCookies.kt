package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import io.ktor.http.*
import io.ktor.server.application.*

/** HttpOnly refresh-token cookie. Only sent to the auth endpoints that need it. */
const val REFRESH_TOKEN_COOKIE = "refresh_token"
private const val REFRESH_COOKIE_PATH = "/api/v1/auth"

/**
 * The web session's cookies (AOS-109). Both are HttpOnly + SameSite=Strict, and Secure off localhost.
 *
 * - `access_token`: the short-lived JWT (expires per `ACCESS_TOKEN_TTL`). The **cookie** lives as long
 *   as the session, so the web page guard still sees a session once the JWT inside has expired. The
 *   API then answers AUTH_001 and the client refreshes.
 * - `refresh_token`: the session secret, scoped to `/api/v1/auth`. Never exposed to JavaScript.
 */
class AuthCookies(private val config: AppConfig) {

    private val sessionMaxAge = (config.auth.refreshTokenTtlDays * 24 * 3600).toInt()
    private val secure = !config.app.appUrl.startsWith("http://localhost")

    fun setSession(call: ApplicationCall, accessToken: String, refreshToken: String) {
        setAccess(call, accessToken)
        call.response.cookies.append(cookie(REFRESH_TOKEN_COOKIE, refreshToken, sessionMaxAge, REFRESH_COOKIE_PATH))
    }

    fun setAccess(call: ApplicationCall, accessToken: String) {
        call.response.cookies.append(cookie(ACCESS_TOKEN_COOKIE, accessToken, sessionMaxAge, "/"))
    }

    fun clear(call: ApplicationCall) {
        call.response.cookies.append(cookie(ACCESS_TOKEN_COOKIE, "", 0, "/"))
        call.response.cookies.append(cookie(REFRESH_TOKEN_COOKIE, "", 0, REFRESH_COOKIE_PATH))
    }

    fun refreshToken(call: ApplicationCall): String? = call.request.cookies[REFRESH_TOKEN_COOKIE]?.takeIf { it.isNotBlank() }

    private fun cookie(name: String, value: String, maxAge: Int, path: String) = Cookie(
        name = name, value = value, maxAge = maxAge, path = path,
        httpOnly = true, secure = secure, extensions = mapOf("SameSite" to "Strict")
    )
}
