package dev.adminos.api.security

import dev.adminos.api.domain.forecast.SalaryChoiceRepository
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import dev.adminos.api.security.SecurityTestKit.newUser
import dev.adminos.api.security.SecurityTestKit.realApp
import dev.adminos.api.security.SecurityTestKit.tokenFor
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.koin.ktor.ext.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PUT /api/v1/forecast/salary in the real application (AOS-722): CSRF, body cap, rate limit, session only. */
class SalaryChoiceSecurityTest {

    private val owner = newUser("salary")
    private val correct = """{"action":"correct","payer":"ACME","day":1,"amount":"85000"}"""

    private suspend fun io.ktor.client.HttpClient.putSalary(body: String = correct, headers: HttpRequestBuilder.() -> Unit) =
        put("/api/v1/forecast/salary") { cookie(ACCESS_TOKEN_COOKIE, tokenFor(owner)); contentType(ContentType.Application.Json); setBody(body); headers() }

    @Test
    fun `a change from another site, or with no origin at all, is refused and stores nothing`() = realApp { app ->
        val forged = listOf<HttpRequestBuilder.() -> Unit>(
            {},
            { header(HttpHeaders.Origin, "https://evil.example") },
            { header("Sec-Fetch-Site", "cross-site") },
            { header("Sec-Fetch-Site", "same-site") },
            { header("Sec-Fetch-Site", "same-origin"); header(HttpHeaders.Origin, "https://evil.example") },
        )
        for (headers in forged) {
            val res = client.putSalary(headers = headers)
            assertEquals(HttpStatusCode.Forbidden, res.status, res.bodyAsText())
            assertTrue("CSRF_001" in res.bodyAsText())
        }
        assertNull(app.get<SalaryChoiceRepository>().find(owner.id))
        val ours = client.putSalary { header(HttpHeaders.Origin, SecurityTestKit.config.app.appUrl) }
        assertEquals(HttpStatusCode.OK, ours.status, ours.bodyAsText())
        assertEquals("ACME", app.get<SalaryChoiceRepository>().find(owner.id)?.payer)
    }

    @Test
    fun `an oversized body is refused before it is read`() = realApp { app ->
        val huge = """{"action":"correct","payer":"${"A".repeat(1_100_000)}","day":1,"amount":"1"}"""
        val res = client.putSalary(huge) { header("Sec-Fetch-Site", "same-origin") }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertNull(app.get<SalaryChoiceRepository>().find(owner.id))
    }

    @Test
    fun `changes are limited to 20 a minute per user`() = realApp { _ ->
        val statuses = (1..21).map { client.putSalary { header("Sec-Fetch-Site", "same-origin") }.status }
        assertEquals(List(20) { HttpStatusCode.OK }, statuses.take(20))
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }

    @Test
    fun `without a session neither route answers, whatever the body claims`() = realApp { app ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/forecast/salary").status)
        val res = client.put("/api/v1/forecast/salary") {
            header("Sec-Fetch-Site", "same-origin"); contentType(ContentType.Application.Json)
            setBody("""{"action":"correct","payer":"ACME","day":1,"amount":"1","userId":"${owner.id}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertNull(app.get<SalaryChoiceRepository>().find(owner.id))
    }
}
