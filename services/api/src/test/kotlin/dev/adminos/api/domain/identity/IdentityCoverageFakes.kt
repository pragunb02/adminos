package dev.adminos.api.domain.identity

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.adminos.api.config.AuthConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** AOS-312: an auth config for unit tests (no env vars). */
internal fun identityTestAuthConfig(
    jwtSecret: String = "test-secret-that-is-at-least-32-bytes-long!",
    issuer: String = "adminos-test",
    ownerEmail: String = "pragun.b@gmail.com" // the fake Google user's email (FakeGoogleServer)
) = AuthConfig(
    jwtSecret = jwtSecret,
    jwtIssuer = issuer,
    accessTokenTtlSeconds = 900,
    refreshTokenTtlDays = 30,
    googleClientId = "test-client-id.apps.googleusercontent.com",
    googleClientSecret = "test-client-secret",
    googlePubsubAudience = "",
    ownerEmail = ownerEmail
)

/** A unique client IP per test, so the process-wide auth rate limiter never couples tests. */
private val ipCounter = AtomicInteger(1)
internal fun uniqueTestIp(): String {
    val n = ipCounter.getAndIncrement()
    return "10.31.${(n shr 8) and 255}.${n and 255}"
}

/**
 * A local stand-in for Google's OAuth token and userinfo endpoints (JDK HttpServer on 127.0.0.1,
 * an ephemeral port). Records what the client sent so tests can assert the wire format.
 */
internal class FakeGoogleServer : AutoCloseable {
    @Volatile var tokenStatus = 200
    @Volatile var tokenBody = """{"access_token":"ya29.a0-test-access","id_token":"eyJ.test","token_type":"Bearer","expires_in":3599,"refresh_token":"1//0g-test-refresh","scope":"openid email https://www.googleapis.com/auth/gmail.readonly"}"""
    @Volatile var userInfoStatus = 200
    @Volatile var userInfoBody = userInfo()

    /** Raw bodies of the token requests received. */
    val tokenRequests = CopyOnWriteArrayList<String>()
    /** Authorization headers of the userinfo requests received. */
    val userInfoAuthorizations = CopyOnWriteArrayList<String?>()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/token") { ex ->
            tokenRequests += ex.requestBody.readAllBytes().decodeToString()
            ex.reply(tokenStatus, tokenBody)
        }
        createContext("/userinfo") { ex ->
            userInfoAuthorizations += ex.requestHeaders.getFirst("Authorization")
            ex.reply(userInfoStatus, userInfoBody)
        }
        start()
    }

    private val base get() = "http://127.0.0.1:${server.address.port}"
    val tokenUrl get() = "$base/token"
    val userInfoUrl get() = "$base/userinfo"

    fun client(config: AuthConfig = identityTestAuthConfig()) = GoogleOAuthClient(config, tokenUrl, userInfoUrl)

    /** The form fields of the last token request, decoded as a standards-compliant server would. */
    fun lastTokenForm(): Map<String, String> = tokenRequests.last().split('&').associate {
        val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
        URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }

    override fun close() = server.stop(0)

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    companion object {
        fun userInfo(
            sub: String = "109876543210987654321",
            email: String = "pragun.b@gmail.com",
            name: String? = "Pragun Bhutani",
            picture: String? = "https://lh3.googleusercontent.com/a/photo",
            emailVerified: Boolean? = true
        ): String = buildString {
            append("""{"sub":"$sub","email":"$email"""")
            if (name != null) append(""","name":"$name"""")
            if (picture != null) append(""","picture":"$picture"""")
            if (emailVerified != null) append(""","email_verified":$emailVerified""")
            append("}")
        }
    }
}
