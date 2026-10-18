package dev.adminos.api.infrastructure.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.adminos.api.infrastructure.database.DatabaseFactory
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-628 (AUD-24): what the API writes to its logs never carries query strings, statement values or caller text. */
class LoggingHygieneTest {

    /** Collects what [loggerName] logs while [block] runs (formatted messages plus any throwable's text). */
    private suspend fun captured(loggerName: String, block: suspend () -> Unit): String {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.joinToString("\n") { e ->
            e.formattedMessage + (e.throwableProxy?.let { " | " + it.className + ": " + it.message } ?: "")
        }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureRequestId(); configureLogging(); configureSerialization(); configureErrorHandling()
            routing {
                get("/api/v1/search") { call.respondText("ok") }
                get("/api/v1/connect") { call.respondRedirect("https://accounts.example.com/consent?state=STATE-SECRET-123&code_challenge=CH") }
                get("/api/v1/db") {
                    throw SQLException("ERROR: duplicate key value violates unique constraint \"uq_users_email\"\n  Detail: Key (email)=(ravi@example.com) already exists.", "23505")
                }
                get("/api/v1/db-wrapped") {
                    throw IllegalStateException("wrapped", SQLException("ERROR: insert failed  Detail: Failing row contains (SWIGGY, 249.00)", "23502"))
                }
            }
        }
        block()
    }

    @Test
    fun `request lines have the path but never the query string`() = app {
        val logs = captured(REQUEST_LOGGER) {
            client.get("/api/v1/search?q=SWIGGY-249&cursor=c2VjcmV0")
        }
        assertTrue("GET /api/v1/search" in logs && "200" in logs, logs)
        assertFalse("SWIGGY" in logs || "c2VjcmV0" in logs || "?" in logs, logs)
    }

    @Test
    fun `a redirect's Location is never logged`() = app {
        val client = createClient { followRedirects = false }
        val logs = captured(REQUEST_LOGGER) { client.get("/api/v1/connect") }
        assertTrue("302 GET /api/v1/connect" in logs, logs)
        assertFalse("STATE-SECRET-123" in logs || "accounts.example.com" in logs, logs)
    }

    @Test
    fun `a database error is logged by kind and SQLState, never with the statement values`() = app {
        val logs = captured("ErrorHandling") {
            assertEquals(HttpStatusCode.InternalServerError, client.get("/api/v1/db").status)
            val wrapped = client.get("/api/v1/db-wrapped")
            assertEquals(HttpStatusCode.InternalServerError, wrapped.status)
            assertFalse("SWIGGY" in wrapped.bodyAsText())
        }
        assertTrue("SQLState 23505" in logs && "SQLState 23502" in logs, logs)
        assertFalse("ravi@example.com" in logs || "SWIGGY" in logs || "249.00" in logs, logs)
    }

    @Test
    fun `other unhandled errors are still logged with their exception`() {
        assertEquals(null, sqlCause(IllegalStateException("x")))
        val inner = SQLException("x", "08006")
        assertEquals(inner, sqlCause(RuntimeException(RuntimeException(inner))))
    }

    @Test
    fun `the database URL is logged without credentials or parameters`() {
        val secret = java.util.UUID.randomUUID().toString() // made per run: no literal credential in the repo
        assertEquals("jdbc:postgresql://db:5432/adminos", DatabaseFactory.redactedUrl("jdbc:postgresql://db:5432/adminos"))
        assertEquals("jdbc:postgresql://db:5432/adminos", DatabaseFactory.redactedUrl("jdbc:postgresql://db:5432/adminos?ssl=true&pw=$secret"))
        assertEquals("jdbc:postgresql://db/adminos", DatabaseFactory.redactedUrl("jdbc:postgresql://me:$secret@db/adminos"))
    }
}
