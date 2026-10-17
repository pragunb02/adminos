package dev.adminos.api.security

import dev.adminos.api.domain.identity.DEVICE_INGEST_SMS
import dev.adminos.api.domain.identity.DeviceTokenService
import dev.adminos.api.domain.identity.Scopes
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import dev.adminos.api.security.SecurityTestKit.newUser
import dev.adminos.api.security.SecurityTestKit.realApp
import dev.adminos.api.security.SecurityTestKit.tokenFor
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every API route requires a signed-in session unless it is on the reviewed allowlist below (AOS-619, D-91, D-100).
 *
 * The inventory is read from the **real** application's routing tree, so a new route is covered the moment it is
 * registered: if it isn't inside `authenticate("auth-jwt")` and isn't allowlisted, this test fails CI. It then
 * also calls every protected route without credentials, and with the wrong kind of token, and expects 401, so a
 * route that is declared protected but somehow isn't (e.g. optional authentication) fails too.
 *
 * **Adding an allowlist entry is a security decision:** say why in the ticket's Security section, name the control
 * that replaces the session, and add attack tests for it (docs/ENGINEERING.md §4a).
 */
class RouteInventoryTest {

    data class Endpoint(val method: HttpMethod, val path: String) {
        override fun toString() = "${method.value} $path"
    }

    data class Registered(val endpoint: Endpoint, val providers: List<String?>)

    companion object {
        /** No session needed. Each entry names what protects it instead. */
        val PUBLIC: Map<Endpoint, String> = mapOf(
            Endpoint(HttpMethod.Get, "/health") to "liveness probe; returns only {status: ok}",
            Endpoint(HttpMethod.Get, "/api/v1/auth/google/start") to "starts a sign-in: issues a single-use state + PKCE bound to the browser by cookie (AOS-618); returns only Google's URL",
            Endpoint(HttpMethod.Post, "/api/v1/auth/google") to "sign-in itself: Google code exchange, owner-only and pinned (AOS-614), rate limited, body capped (AOS-615)",
            Endpoint(HttpMethod.Post, "/api/v1/auth/refresh") to "authenticated by the HttpOnly refresh cookie (path /api/v1/auth); must work after the access JWT expired",
            Endpoint(HttpMethod.Post, "/api/v1/auth/logout") to "revokes only the session of the presented refresh cookie; nothing to gain without one",
            Endpoint(HttpMethod.Post, "/api/v1/webhooks/gmail") to "Google Pub/Sub push: Google OIDC token, our audience and push service account (AOS-609); 503 when unconfigured",
        )

        /** Authenticated, but not by a browser session: the provider each must use. */
        val OTHER_AUTH: Map<Endpoint, String> = mapOf(
            Endpoint(HttpMethod.Post, "/api/v1/ingest/sms/raw") to DEVICE_INGEST_SMS, // device token, header only, scope ingest:sms (D-36)
        )

        const val SESSION = "auth-jwt"

        /** The routes registered in [app], with the authentication providers that guard each. */
        fun inventory(app: Application): List<Registered> = app.routingRoot.getAllRoutes().map { leaf ->
            val chain = generateSequence(leaf) { it.parent }.toList().reversed()
            var method: HttpMethod? = null
            val providers = mutableListOf<String?>()
            val path = StringBuilder()
            for (node in chain) {
                when (val s = node.selector) {
                    is HttpMethodRouteSelector -> method = s.method
                    is PathSegmentConstantRouteSelector -> path.append('/').append(s.value)
                    is PathSegmentParameterRouteSelector -> path.append("/{").append(s.name).append('}')
                    is AuthenticationRouteSelector -> providers += s.names
                    is RootRouteSelector -> Unit
                    is TrailingSlashRouteSelector -> path.append('/')
                    else -> fail("Unreviewed route selector ${s::class.simpleName} in ${leaf}: teach the inventory about it")
                }
            }
            Registered(Endpoint(method ?: fail("Route without a method: $leaf"), path.toString().ifEmpty { "/" }), providers)
        }

        private fun concrete(path: String) = path.replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
    }

    @Test
    fun `every route needs a session unless it is on the reviewed allowlist`() = realApp { app ->
        val routes = inventory(app)
        assertTrue(routes.size > 50, "the inventory should see the whole API, saw ${routes.size}")
        val violations = routes.mapNotNull { r ->
            when (r.endpoint) {
                in PUBLIC -> r.takeIf { it.providers.isNotEmpty() }?.let { "${it.endpoint} is allowlisted as public but is behind ${it.providers}: move it off the list" }
                in OTHER_AUTH -> r.takeIf { it.providers != listOf(OTHER_AUTH[it.endpoint]) }?.let { "${it.endpoint} must use only ${OTHER_AUTH[it.endpoint]}, uses ${it.providers}" }
                else -> r.takeIf { it.providers != listOf(SESSION) }?.let {
                    "${it.endpoint} is not behind authenticate(\"$SESSION\") (providers: ${it.providers}). " +
                        "Protect it, or add it to the reviewed allowlist with the control that replaces the session"
                }
            }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `the allowlist has no stale entries`() = realApp { app ->
        val registered = inventory(app).map { it.endpoint }.toSet()
        assertEquals(emptySet(), (PUBLIC.keys + OTHER_AUTH.keys) - registered, "allowlisted but no longer registered: remove them")
    }

    @Test
    fun `every protected route answers 401 without credentials`() = realApp { app ->
        val protectedRoutes = inventory(app).filter { it.endpoint !in PUBLIC }
        val wrong = protectedRoutes.mapNotNull { r ->
            val res = client.request(concrete(r.endpoint.path)) {
                method = r.endpoint.method
                header(HttpHeaders.Origin, SecurityTestKit.config.app.appUrl)
            }
            if (res.status == HttpStatusCode.Unauthorized && "AUTH_002" in res.bodyAsText()) null else "${r.endpoint} → ${res.status}"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a device token never opens a session route, in the header or as the cookie`() = realApp { app ->
        val device = app.get<DeviceTokenService>().issue(UUID.randomUUID(), "attacker Mac", setOf(Scopes.INGEST_SMS)).token
        val sessionRoutes = inventory(app).filter { it.providers == listOf(SESSION) }
        val wrong = sessionRoutes.flatMap { r ->
            listOf(
                client.request(concrete(r.endpoint.path)) { method = r.endpoint.method; bearerAuth(device) },
                client.request(concrete(r.endpoint.path)) { method = r.endpoint.method; cookie(ACCESS_TOKEN_COOKIE, device); header(HttpHeaders.Origin, SecurityTestKit.config.app.appUrl) }
            ).filter { it.status != HttpStatusCode.Unauthorized }.map { "${r.endpoint} → ${it.status}" }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a session token never opens a device route`() = realApp { _ ->
        val session = tokenFor(newUser("ravi"))
        for (e in OTHER_AUTH.keys) {
            val asHeader = client.request(e.path) { method = e.method; bearerAuth(session); contentType(ContentType.Application.Json); setBody("""{"messages":[]}""") }
            assertEquals(HttpStatusCode.Unauthorized, asHeader.status, "$e with an access token in the header")
            // Device auth reads the header only: a browser's cookie must not count (D-36).
            val asCookie = client.request(e.path) { method = e.method; cookie(ACCESS_TOKEN_COOKIE, session); header(HttpHeaders.Origin, SecurityTestKit.config.app.appUrl); contentType(ContentType.Application.Json); setBody("""{"messages":[]}""") }
            assertEquals(HttpStatusCode.Unauthorized, asCookie.status, "$e with an access token as the cookie")
        }
    }

    @Test
    fun `the public sign-in routes give nothing away without their own credential`() = realApp { _ ->
        // No refresh cookie: refresh is refused, logout does nothing, the webhook refuses (unconfigured → 503).
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/refresh").status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/logout").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/v1/webhooks/gmail") { contentType(ContentType.Application.Json); setBody("{}") }.status)
        // A forged code with a foreign redirect is refused before Google is ever called.
        val google = client.post("/api/v1/auth/google") { contentType(ContentType.Application.Json); setBody("""{"code":"forged","redirectUri":"https://evil.example/cb"}""") }
        assertEquals(HttpStatusCode.BadRequest, google.status)
    }
}
