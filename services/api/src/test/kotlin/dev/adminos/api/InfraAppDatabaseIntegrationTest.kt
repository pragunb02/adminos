package dev.adminos.api

import com.zaxxer.hikari.HikariDataSource
import dev.adminos.api.InfraIngestTestKit.config
import dev.adminos.api.di.buildAppModule
import dev.adminos.api.domain.identity.OnboardingStatus
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.repositories.ExposedIngestEventRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedUserRepository
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `Application.module` in the database configuration: migrate, connect, and serve from Postgres (AOS-312). */
@Tag("integration")
class InfraAppDatabaseIntegrationTest {

    @Test
    fun `the app starts on Postgres and stores intake events there`() {
        val ds = PostgresTestDatabase.dataSource as HikariDataSource
        val dbConfig = config.copy(database = config.database.copy(url = ds.jdbcUrl, user = ds.username, password = ds.password, maxPoolSize = 2),
            // The shared test database is used by other tests too: no scheduled jobs touching their data.
            scheduler = dev.adminos.api.config.SchedulerConfig(enabled = false))
        val user = runBlocking {
            ExposedUserRepository().save(User(email = "db-${UUID.randomUUID()}@example.com", googleId = "g-${UUID.randomUUID()}",
                role = UserRole.OWNER, onboardingStatus = OnboardingStatus.STARTED))
        }
        // With a database every access token must name a live session (AOS-620).
        val session = runBlocking {
            dev.adminos.api.infrastructure.database.repositories.ExposedSessionRepository().save(dev.adminos.api.domain.identity.Session(
                userId = user.id, tokenHash = dev.adminos.api.domain.identity.JwtService.hashToken(UUID.randomUUID().toString()),
                expiresAt = java.time.Instant.now().plusSeconds(3600), secondFactorAt = java.time.Instant.now())) // passed its passkey (AOS-617)
        }
        testApplication {
            environment { this.config = MapApplicationConfig() }
            application { module(dbConfig, diModule = buildAppModule(useDatabase = true, env = { null }), useDatabase = true) }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            val sessionless = client.get("/api/v1/transactions") { cookie(ACCESS_TOKEN_COOKIE, InfraIngestTestKit.tokenFor(user)) }
            assertEquals(HttpStatusCode.Unauthorized, sessionless.status, "a correctly signed token without a session is refused with real data")
            val res = client.post("/api/v1/transactions/cash") {
                cookie(ACCESS_TOKEN_COOKIE, InfraIngestTestKit.jwtService.generateAccessToken(user, session.id))
                header(HttpHeaders.Origin, InfraIngestTestKit.config.app.appUrl) // the CSRF check (AOS-618)
                contentType(ContentType.Application.Json)
                setBody("""{"clientRef":"cash-db-0001","amount":"80","category":"food","note":"Auto"}""")
            }
            assertEquals(HttpStatusCode.Accepted, res.status, res.bodyAsText())
        }
        val stored = runBlocking { ExposedIngestEventRepository().findByUser(user.id) }
        assertTrue(stored.any { it.sourceRef == "cash:cash-db-0001" }, "written through the Exposed repositories")
    }
}
