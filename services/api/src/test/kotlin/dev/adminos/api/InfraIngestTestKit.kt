package dev.adminos.api

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.configureAuthentication
import dev.adminos.api.infrastructure.plugins.configureErrorHandling
import dev.adminos.api.infrastructure.plugins.configureRequestId
import dev.adminos.api.infrastructure.plugins.configureSerialization
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Shared helpers for the AOS-312 infra / DI / ingestion coverage tests. */
object InfraIngestTestKit {
    val config: AppConfig = AppConfig.load()
    val jwtService = JwtService(config.auth)
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    fun user(timezone: String = "Asia/Kolkata") =
        User(id = UUID.randomUUID(), email = "ravi@example.com", googleId = "g-${UUID.randomUUID()}", role = UserRole.OWNER, timezone = timezone)

    fun tokenFor(user: User): String = jwtService.generateAccessToken(user)

    /** A route test with the production plugins (serialization, request id, errors, JWT auth) under /api/v1. */
    fun routeTest(routes: Route.() -> Unit, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { routes() } }
        }
        block()
    }

    fun ist(day: Int, h: Int, m: Int = 0, month: Int = 10): Instant =
        LocalDate.of(2026, month, day).atTime(LocalTime.of(h, m)).atZone(IST).toInstant()

    fun txn(
        user: UUID, type: TransactionType, amount: String = "249.00", at: Instant = ist(1, 9),
        source: String = "sms", last4: String? = null, merchant: String? = null, raw: String? = null,
        vpa: String? = null, ref: String? = null, method: PaymentMethod? = null
    ) = Transaction(
        userId = user, sourceType = source, type = type, amount = BigDecimal(amount), accountLast4 = last4,
        merchantName = merchant, merchantRaw = raw, upiVpa = vpa, paymentRef = ref, paymentMethod = method, transactedAt = at
    )

    private val withDefaults = Json { encodeDefaults = true }
    private val withoutDefaults = Json { encodeDefaults = false }

    /**
     * Decodes each JSON sample, then checks it survives a round trip both with and without default values
     * written (the two ways the wire format is produced). Returns the decoded values.
     */
    fun <T> assertRoundTrips(serializer: KSerializer<T>, vararg samples: String): List<T> = samples.map { sample ->
        val value = withDefaults.decodeFromString(serializer, sample)
        for (json in listOf(withDefaults, withoutDefaults)) {
            assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)), "round trip of $sample")
        }
        value
    }

    /** A body without the required fields is rejected, never filled with made-up values. */
    fun assertRejectsMissingFields(serializer: DeserializationStrategy<Any?>, json: String = "{}") {
        assertFailsWith<SerializationException> { withDefaults.decodeFromString(serializer, json) }
    }

    /** The generated serializer of a private nested wire class, e.g. `HttpStatementParser$WireRow`. */
    @Suppress("UNCHECKED_CAST")
    fun privateSerializer(binaryName: String): KSerializer<Any?> =
        Class.forName("$binaryName\$\$serializer").getDeclaredField("INSTANCE")
            .apply { isAccessible = true }.get(null) as KSerializer<Any?>
}
