package dev.adminos.api.domain.ingestion.statements

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatementRoutesTest {

    private val jwt = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val token = jwt.generateAccessToken(user)
    private var behaviour: (String?) -> ParsedStatement = { _ ->
        ParsedStatement("sbi", "5678", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1),
            listOf(StatementRow(LocalDate.of(2026, 10, 1), "ZOMATO", BigDecimal("450.00"), null, null, null)), 2, listOf("w"))
    }
    private var seenPassword: String? = "unset"
    private val parser = object : StatementParser {
        override suspend fun parse(file: ByteArray, filename: String, password: String?): ParsedStatement {
            seenPassword = password; return behaviour(password)
        }
    }

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { statementRoutes(StatementImportService(parser, IngestEventService(InMemoryIngestEventRepository()), dev.adminos.api.domain.balances.BalanceService(dev.adminos.api.domain.balances.InMemoryBalanceRepository()))) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.upload(password: String? = null, withFile: Boolean = true, auth: Boolean = true) =
        client.post("/api/v1/statements") {
            if (auth) cookie(ACCESS_TOKEN_COOKIE, token)
            setBody(MultiPartFormDataContent(formData {
                if (withFile) append("file", "Ravi_Oct.csv".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"Ravi_Oct.csv\"")
                })
                password?.let { append("password", it) }
            }))
        }

    @Test
    fun `upload returns the import summary`() = routeTest {
        val res = upload(password = "01011990")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = res.bodyAsText()
        assertTrue("\"bankCode\":\"sbi\"" in body && "\"accepted\":1" in body && "\"skippedRows\":2" in body, body)
        assertEquals("01011990", seenPassword)
    }

    @Test
    fun `missing file, missing auth`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, upload(withFile = false).status)
        assertEquals(HttpStatusCode.Unauthorized, upload(auth = false).status)
    }

    @Test
    fun `parser error codes reach the client`() = routeTest {
        behaviour = { throw StatementParseException("PASSWORD_REQUIRED", "This statement is password-protected", clientError = true) }
        val res = upload()
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        assertTrue("PASSWORD_REQUIRED" in res.bodyAsText())
        behaviour = { throw StatementParseException("PARSER_UNAVAILABLE", "The statement parser isn't reachable", clientError = false) }
        assertEquals(HttpStatusCode.ServiceUnavailable, upload().status)
    }
}
