package dev.adminos.api.domain.financial

import dev.adminos.api.domain.financial.FinancialCoverageSupport.IST
import dev.adminos.api.domain.financial.FinancialCoverageSupport.data
import dev.adminos.api.domain.financial.FinancialCoverageSupport.dataArray
import dev.adminos.api.domain.financial.FinancialCoverageSupport.errorCode
import dev.adminos.api.domain.financial.FinancialCoverageSupport.getAs
import dev.adminos.api.domain.financial.FinancialCoverageSupport.isNull
import dev.adminos.api.domain.financial.FinancialCoverageSupport.money
import dev.adminos.api.domain.financial.FinancialCoverageSupport.newUser
import dev.adminos.api.domain.financial.FinancialCoverageSupport.pagination
import dev.adminos.api.domain.financial.FinancialCoverageSupport.routeTest
import dev.adminos.api.domain.financial.FinancialCoverageSupport.str
import dev.adminos.api.domain.financial.FinancialCoverageSupport.tokenFor
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Disabled
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/bills list, upcoming and detail (AOS-312), on a fixed clock: 16 Oct 2026 in India. */
class FinancialBillRoutesCoverageTest {

    private val user = newUser()
    private val token = tokenFor(user)
    private val today = LocalDate.of(2026, 10, 16)
    private val clock = Clock.fixed(LocalDateTime.of(2026, 10, 16, 10, 0).atZone(IST).toInstant(), IST)
    private val bills = InMemoryBillRepository()
    private val service = BillTrackingService(bills, InMemoryTransactionRepository(), dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository(), clock))

    private fun billTest(block: suspend ApplicationTestBuilder.() -> Unit) = routeTest({ billRoutes(service) }, block)

    private fun bill(biller: String, due: LocalDate, status: BillStatus = BillStatus.UPCOMING, owner: UUID = user.id, amount: String = "1499.00") =
        Bill(userId = owner, billType = BillType.CREDIT_CARD, billerName = biller, amount = BigDecimal(amount), dueDate = due, status = status)

    private fun JsonObject.billers() = dataArray().map { it.jsonObject.str("billerName") }

    @Test
    fun `list returns only my bills sorted by due date with the total`() = billTest {
        runBlocking {
            bills.save(bill("Tata Power", today.plusDays(9)))
            bills.save(bill("HDFC Credit Card", today.plusDays(2)))
            bills.save(bill("ICICI Card", today.plusDays(1), owner = UUID.randomUUID()))
        }
        val (status, body) = getAs(token, "/api/v1/bills")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf("HDFC Credit Card", "Tata Power"), body.billers())
        assertEquals(2, body.pagination()["total"]!!.jsonPrimitive.int)
        assertEquals(false, body.pagination()["hasMore"]!!.jsonPrimitive.boolean)
        assertTrue(body.pagination().isNull("cursor"))
    }

    @Test
    fun `status and date range filters narrow the list`() = billTest {
        runBlocking {
            bills.save(bill("Airtel Postpaid", LocalDate.of(2026, 10, 5), BillStatus.PAID))
            bills.save(bill("Jio Fiber", LocalDate.of(2026, 10, 20), BillStatus.PAID))
            bills.save(bill("BESCOM", LocalDate.of(2026, 10, 21)))
        }
        val (_, body) = getAs(token, "/api/v1/bills?status=paid&from=2026-10-10&to=2026-10-31")
        assertEquals(listOf("Jio Fiber"), body.billers())
        assertEquals(2, body.pagination()["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a full page returns a cursor that fetches the next page`() = billTest {
        runBlocking { (1..3).forEach { bills.save(bill("Biller $it", today.plusDays(it.toLong()))) } }
        val (_, page1) = getAs(token, "/api/v1/bills?limit=2")
        assertEquals(listOf("Biller 1", "Biller 2"), page1.billers())
        assertEquals(true, page1.pagination()["hasMore"]!!.jsonPrimitive.boolean)
        val cursor = page1.pagination().str("cursor")

        val (_, page2) = getAs(token, "/api/v1/bills?limit=2&cursor=${cursor.encodeURLParameter()}")
        assertEquals(listOf("Biller 3"), page2.billers())
        assertEquals(false, page2.pagination()["hasMore"]!!.jsonPrimitive.boolean)
    }
    @Test
    fun `bills sharing a due date across a page boundary are all listed`() = billTest {
        runBlocking { listOf("HDFC Credit Card", "SBI Card", "Tata Power").forEach { bills.save(bill(it, today.plusDays(5))) } }
        val (_, page1) = getAs(token, "/api/v1/bills?limit=2")
        val cursor = page1.pagination().str("cursor")
        val (_, page2) = getAs(token, "/api/v1/bills?limit=2&cursor=${cursor.encodeURLParameter()}")
        assertEquals(3, (page1.billers() + page2.billers()).toSet().size)
    }

    @Test
    fun `invalid status and date filters are 400s`() = billTest {
        assertEquals(HttpStatusCode.BadRequest, getAs(token, "/api/v1/bills?status=late").first)
        assertEquals(HttpStatusCode.BadRequest, getAs(token, "/api/v1/bills?from=16-10-2026").first)
    }

    @Test
    fun `upcoming lists unpaid bills due in the next 30 days`() = billTest {
        runBlocking {
            bills.save(bill("HDFC Credit Card", today.plusDays(3)))
            bills.save(bill("Tata Power", today.plusDays(10), BillStatus.DUE))
            bills.save(bill("LIC Premium", today.plusDays(45)))
            bills.save(bill("Airtel Postpaid", today.plusDays(2), BillStatus.PAID))
            bills.save(bill("BESCOM", today.minusDays(1)))
        }
        val (status, body) = getAs(token, "/api/v1/bills/upcoming")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf("HDFC Credit Card", "Tata Power"), body.billers())
    }

    @Test
    fun `detail returns my bill with exact amounts and payment fields`() = billTest {
        val txnId = UUID.randomUUID()
        val paidAt = Instant.parse("2026-10-14T06:30:00Z")
        val saved = runBlocking {
            bills.save(bill("HDFC Credit Card", today.minusDays(1), BillStatus.PAID, amount = "15230.45").copy(
                minimumDue = BigDecimal("761.52"), paidAt = paidAt, paidAmount = BigDecimal("15230.45"), paymentTxnId = txnId))
        }
        val (status, body) = getAs(token, "/api/v1/bills/${saved.id}")
        assertEquals(HttpStatusCode.OK, status)
        val data = body.data()
        assertEquals("credit_card", data.str("billType"))
        assertEquals("paid", data.str("status"))
        assertEquals(0, BigDecimal("15230.45").compareTo(data.money("amount")))
        assertEquals(0, BigDecimal("761.52").compareTo(data.money("minimumDue")))
        assertEquals(paidAt.toString(), data.str("paidAt"))
        assertEquals(txnId.toString(), data.str("paymentTxnId"))
        assertEquals("2026-10-15", data.str("dueDate"))
    }

    @Test
    fun `detail of an unpaid bill has null payment fields`() = billTest {
        val saved = runBlocking { bills.save(bill("Tata Power", today.plusDays(4))) }
        val data = getAs(token, "/api/v1/bills/${saved.id}").second.data()
        assertTrue(data.isNull("minimumDue") && data.isNull("paidAt") && data.isNull("paidAmount") && data.isNull("paymentTxnId"))
    }

    @Test
    fun `another user's bill is a 404, not a leak`() = billTest {
        val theirs = runBlocking { bills.save(bill("ICICI Card", today.plusDays(2), owner = UUID.randomUUID())) }
        val (status, body) = getAs(token, "/api/v1/bills/${theirs.id}")
        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals("BILL_001", body.errorCode())
    }

    @Test
    fun `an unknown bill id is a 404`() = billTest {
        val (status, body) = getAs(token, "/api/v1/bills/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals("BILL_001", body.errorCode())
    }

    @Test
    fun `a malformed bill id is a 400`() = billTest {
        assertEquals(HttpStatusCode.BadRequest, getAs(token, "/api/v1/bills/not-a-uuid").first)
    }

    @Test
    fun `bills need a session`() = billTest {
        assertEquals(HttpStatusCode.Unauthorized, getAs(null, "/api/v1/bills").first)
    }
}
