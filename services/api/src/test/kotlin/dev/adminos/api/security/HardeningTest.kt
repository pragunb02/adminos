package dev.adminos.api.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.ingestion.connection.GmailOAuth
import dev.adminos.api.domain.ingestion.connection.InMemoryOAuthStateRepository
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
import dev.adminos.api.infrastructure.plugins.*
import dev.adminos.api.security.SecurityTestKit.newUser
import dev.adminos.api.security.SecurityTestKit.realApp
import dev.adminos.api.security.SecurityTestKit.tokenFor
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Date
import java.util.UUID
import kotlin.test.*

/** AOS-618 hardening against the real application: headers, CSRF, client IP trust, JWT strictness, error text, limits. */
class HardeningTest {

    private val appOrigin = originOf(SecurityTestKit.config.app.appUrl)

    // ── Security headers (AUD-07) ──

    @Test
    fun `every API response carries the browser hardening headers`() = realApp { _ ->
        for (res in listOf(client.get("/health"), client.get("/api/v1/transactions"), client.get("/api/v1/nope"))) {
            assertEquals("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'", res.headers["Content-Security-Policy"], "${res.status}")
            assertEquals("nosniff", res.headers["X-Content-Type-Options"])
            assertEquals("DENY", res.headers["X-Frame-Options"])
            assertEquals("no-referrer", res.headers["Referrer-Policy"])
            assertEquals("same-origin", res.headers["Cross-Origin-Resource-Policy"])
            assertEquals("same-origin", res.headers["Cross-Origin-Opener-Policy"])
            assertEquals("no-store", res.headers[HttpHeaders.CacheControl])
            assertTrue(res.headers["Permissions-Policy"].orEmpty().contains("camera=()"))
            assertEquals(1, res.headers.getAll("X-Content-Type-Options")?.size, "set once")
        }
    }

    @Test
    fun `HSTS is sent only when the app is served over https`() {
        fun hsts(appUrl: String) = testApplication {
            environment { config = MapApplicationConfig() }
            application { configureHTTP(appUrl); routing { get("/x") { call.respondText("ok") } } }
            val res = client.get("/x")
            if (appUrl.startsWith("https")) assertEquals("max-age=63072000; includeSubDomains", res.headers[HttpHeaders.StrictTransportSecurity])
            else assertNull(res.headers[HttpHeaders.StrictTransportSecurity])
        }
        hsts("https://adminos.example.in")
        hsts("http://localhost:3000")
    }

    @Test
    fun `origins are normalised the way browsers send them`() {
        assertEquals("https://adminos.example.in", originOf("https://AdminOS.example.in:443/"))
        assertEquals("http://localhost:3000", originOf("http://localhost:3000"))
        assertEquals("http://127.0.0.1", originOf("http://127.0.0.1:80/x"))
        assertFailsWith<IllegalArgumentException> { originOf("localhost:3000") }
    }

    // ── CSRF: Origin / Fetch Metadata on cookie mutations (AUD-08) ──

    private val cash get() = """{"clientRef":"csrf-${UUID.randomUUID()}","amount":"10","category":"food"}"""

    @Test
    fun `a cookie mutation from another site is refused whatever header it shows`() = realApp { _ ->
        val token = tokenFor(newUser("ravi"))
        val forged = listOf<HttpRequestBuilder.() -> Unit>(
            { header(HttpHeaders.Origin, "https://evil.example") },
            { header(HttpHeaders.Origin, "null") },
            { header(HttpHeaders.Origin, "$appOrigin.evil.example") },
            { header("Sec-Fetch-Site", "cross-site") },
            { header("Sec-Fetch-Site", "same-site") },
            { header("Sec-Fetch-Site", "none") },
            { header("Sec-Fetch-Site", "same-origin"); header(HttpHeaders.Origin, "https://evil.example") },
            { }, // neither header: fail closed
        )
        for (attack in forged) {
            val res = client.post("/api/v1/transactions/cash") {
                cookie(ACCESS_TOKEN_COOKIE, token); contentType(ContentType.Application.Json); setBody(cash); attack()
            }
            assertEquals(HttpStatusCode.Forbidden, res.status)
            assertTrue("CSRF_001" in res.bodyAsText())
        }
    }

    @Test
    fun `our own pages pass, by Origin or by Fetch Metadata`() = realApp { _ ->
        val token = tokenFor(newUser("ravi"))
        val ok = listOf<HttpRequestBuilder.() -> Unit>(
            { header(HttpHeaders.Origin, appOrigin) },
            { header("Sec-Fetch-Site", "same-origin") },
            { header("Sec-Fetch-Site", "same-origin"); header(HttpHeaders.Origin, appOrigin) },
        )
        for (browser in ok) {
            val res = client.post("/api/v1/transactions/cash") {
                cookie(ACCESS_TOKEN_COOKIE, token); contentType(ContentType.Application.Json); setBody(cash); browser()
            }
            assertEquals(HttpStatusCode.Accepted, res.status, res.bodyAsText())
        }
    }

    @Test
    fun `requests without session cookies and safe methods are not CSRF-checked`() = realApp { _ ->
        val token = tokenFor(newUser("ravi"))
        // A script or the SMS reader sends its token in the header: a browser can't forge that.
        val bearer = client.post("/api/v1/transactions/cash") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(cash) }
        assertEquals(HttpStatusCode.Accepted, bearer.status)
        val read = client.get("/api/v1/transactions") { cookie(ACCESS_TOKEN_COOKIE, token); header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.OK, read.status)
    }

    @Test
    fun `refresh and logout by cookie are CSRF-checked too`() = realApp { _ ->
        val refresh = client.post("/api/v1/auth/refresh") { cookie("refresh_token", "x"); header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, refresh.status)
        val logout = client.post("/api/v1/auth/logout") { cookie("refresh_token", "x") }
        assertEquals(HttpStatusCode.Forbidden, logout.status)
    }

    // ── Client IP trust (AUD-03) ──

    private fun ipApp(trust: Boolean, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = SecurityTestKit.config.copy(app = SecurityTestKit.config.app.copy(trustForwardedFor = trust))
        environment { config = MapApplicationConfig() }
        application { configurePlugins(cfg, JwtService(cfg.auth)); routing { get("/ip") { call.respondText(call.clientIp) } } }
        block()
    }

    @Test
    fun `by default a client-sent X-Forwarded-For is ignored`() = ipApp(trust = false) {
        assertNotEquals("203.0.113.66", client.get("/ip") { header(HttpHeaders.XForwardedFor, "203.0.113.66") }.bodyAsText())
    }

    @Test
    fun `behind a trusted edge the last hop is the client`() = ipApp(trust = true) {
        assertEquals("203.0.113.9", client.get("/ip") { header(HttpHeaders.XForwardedFor, "6.6.6.6, 203.0.113.9") }.bodyAsText())
    }

    @Test
    fun `TRUST_FORWARDED_FOR accepts only true or false`() {
        assertFalse(AppConfig.trustForwardedFor { null })
        assertFalse(AppConfig.trustForwardedFor { " FALSE " })
        assertTrue(AppConfig.trustForwardedFor { "true" })
        assertFailsWith<IllegalArgumentException> { AppConfig.trustForwardedFor { "yes" } }
    }

    // ── JWT strictness (AUD-14) ──

    private fun forged(alg: JWSAlgorithm, claims: JWTClaimsSet.Builder.() -> Unit, crit: Boolean = false): String {
        val key = SecurityTestKit.config.auth.jwtSecret.toByteArray()
        val header = JWSHeader.Builder(alg).type(JOSEObjectType.JWT).apply { if (crit) criticalParams(setOf("x-adminos")).customParam("x-adminos", 1) }.build()
        val c = JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).claim("typ", "access").claim("email", "x@example.in").claim("role", "owner")
            .issuer(SecurityTestKit.config.auth.jwtIssuer).issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 600_000)).apply(claims).build()
        return SignedJWT(header, c).apply { sign(MACSigner(key)) }.serialize()
    }

    @Test
    fun `only HS256 tokens without critical extensions, with an expiry, issued now are accepted`() {
        val jwt = JwtService(SecurityTestKit.config.auth)
        assertNotNull(jwt.verifyAccessToken(forged(JWSAlgorithm.HS256, {})), "control: a well-formed token passes")
        assertNull(jwt.verifyAccessToken(forged(JWSAlgorithm.HS384, {})))
        assertNull(jwt.verifyAccessToken(forged(JWSAlgorithm.HS256, {}, crit = true)))
        assertNull(jwt.verifyAccessToken(forged(JWSAlgorithm.HS256, { expirationTime(null) })))
        assertNull(jwt.verifyAccessToken(forged(JWSAlgorithm.HS256, { issueTime(Date(System.currentTimeMillis() + 3_600_000)) })), "issued in the future")
    }

    // ── Error text (AUD-15) ──

    @Test
    fun `library error text never reaches the client`() {
        assertEquals("fallback", publicMessage("No enum constant dev.adminos.api.domain.financial.TransactionCategory.X", "fallback"))
        assertEquals("fallback", publicMessage("Invalid UUID string: ' or 1=1 --", "fallback"))
        assertEquals("fallback", publicMessage("java.lang.NullPointerException at foo", "fallback"))
        assertEquals("fallback", publicMessage("x".repeat(301), "fallback"))
        assertEquals("fallback", publicMessage(null, "fallback"))
        assertEquals("to: must be on or after from", publicMessage("to: must be on or after from", "fallback"))
        assertEquals("Field 'amount' is required for type with serial name the request, but it was missing",
            publicMessage("Field 'amount' is required for type with serial name 'dev.adminos.api.domain.ingestion.cash.CashRequest', but it was missing", "fallback"))
        assertEquals("line one line two", publicMessage("line one\r\nline two", "fallback").replace(Regex("\\s+"), " "))
    }

    @Test
    fun `input echoed in a 400 is only ever inert JSON`() = realApp { _ ->
        // Query errors name the bad value on purpose (D-31); it must never be renderable as a page.
        val res = client.get("/api/v1/analytics/summary?from=<script>&to=2026-10-01") { cookie(ACCESS_TOKEN_COOKIE, tokenFor(newUser("ravi"))) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(ContentType.Application.Json, res.contentType()?.withoutParameters())
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
        assertTrue(res.headers["Content-Security-Policy"].orEmpty().startsWith("default-src 'none'"))
    }

    @Test
    fun `a bad date in a body or path is answered without quoting it`() = realApp { _ ->
        val res = client.patch("/api/v1/documents/${UUID.randomUUID()}") {
            cookie(ACCESS_TOKEN_COOKIE, tokenFor(newUser("ravi"))); header(HttpHeaders.Origin, appOrigin)
            contentType(ContentType.Application.Json); setBody("""{"expiresOn":"<img src=x onerror=alert(1)>"}""")
        }
        assertFalse("onerror" in res.bodyAsText(), res.bodyAsText())
    }

    // ── Bounded limiter (AUD-16) ──

    @Test
    fun `the rate limiter keeps a bounded number of keys under a flood of new ones`() {
        val limiter = RateLimiter(maxRequests = 10, windowSeconds = 60, maxKeys = 2_000)
        repeat(20_000) { limiter.isAllowed("spoofed-$it") }
        assertTrue(limiter.trackedKeys <= 2_001, "tracked ${limiter.trackedKeys}")
    }

    // ── Upload forms (AUD-21) ──

    @Test
    fun `a document upload with an extra field, a second file or a repeated field is refused`() = realApp { _ ->
        val token = tokenFor(newUser("ravi"))
        val pdf = "%PDF-1.4\n%".toByteArray()
        val fileHeaders = Headers.build { append(HttpHeaders.ContentType, "application/pdf"); append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"") }
        val forms = listOf(
            io.ktor.client.request.forms.formData { append("category", "tax"); append("file", pdf, fileHeaders); append("smuggled", "x") },
            io.ktor.client.request.forms.formData { append("category", "tax"); append("file", pdf, fileHeaders); append("file", pdf, fileHeaders) },
            io.ktor.client.request.forms.formData { append("category", "tax"); append("category", "other"); append("file", pdf, fileHeaders) },
            io.ktor.client.request.forms.formData { append("category", "tax"); append("notes", "n".repeat(5_000)); append("file", pdf, fileHeaders) },
        )
        for (form in forms) {
            val res = client.post("/api/v1/documents") {
                cookie(ACCESS_TOKEN_COOKIE, token); header(HttpHeaders.Origin, appOrigin)
                setBody(io.ktor.client.request.forms.MultiPartFormDataContent(form))
            }
            assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
            assertTrue("UPLOAD_001" in res.bodyAsText())
        }
    }

    // ── Gmail redirect (AUD-18) ──

    @Test
    fun `connecting Gmail accepts only this app's callback`() {
        val oauth = GmailOAuth(dev.adminos.api.domain.identity.GoogleOAuthClient(SecurityTestKit.config.auth), OAuthStateService(InMemoryOAuthStateRepository(), null),
            "https://adminos.example.in/connections/gmail/callback")
        oauth.requireAllowed("https://adminos.example.in/connections/gmail/callback")
        val e = assertFailsWith<ApiException> { oauth.requireAllowed("https://evil.example/connections/gmail/callback") }
        assertEquals("CONN_005", e.error.code)
    }

    @Test
    fun `the app's Gmail callback is derived from APP_URL`() {
        assertEquals("https://adminos.example.in/connections/gmail/callback", dev.adminos.api.domain.ingestion.gmailCallbackUrl("https://adminos.example.in/"))
    }
}
