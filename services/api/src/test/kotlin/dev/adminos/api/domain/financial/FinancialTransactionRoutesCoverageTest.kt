package dev.adminos.api.domain.financial

import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.FinancialCoverageSupport.data
import dev.adminos.api.domain.financial.FinancialCoverageSupport.dataArray
import dev.adminos.api.domain.financial.FinancialCoverageSupport.errorCode
import dev.adminos.api.domain.financial.FinancialCoverageSupport.getAs
import dev.adminos.api.domain.financial.FinancialCoverageSupport.isNull
import dev.adminos.api.domain.financial.FinancialCoverageSupport.money
import dev.adminos.api.domain.financial.FinancialCoverageSupport.newUser
import dev.adminos.api.domain.financial.FinancialCoverageSupport.patchAs
import dev.adminos.api.domain.financial.FinancialCoverageSupport.routeTest
import dev.adminos.api.domain.financial.FinancialCoverageSupport.str
import dev.adminos.api.domain.financial.FinancialCoverageSupport.tokenFor
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Disabled
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/transactions list filters, summary and PATCH category (AOS-312). */
class FinancialTransactionRoutesCoverageTest {

    private val user = newUser()
    private val token = tokenFor(user)
    private val transactions = InMemoryTransactionRepository()
    private val rules = InMemoryCategoryRuleRepository()
    private val auditRepository = InMemoryAuditRepository()
    private val audit = AuditService(auditRepository, CoroutineScope(Dispatchers.Unconfined))
    private val oct10 = Instant.parse("2026-10-10T06:30:00Z") // 12:00 IST

    private fun txnTest(block: suspend ApplicationTestBuilder.() -> Unit) =
        routeTest({ transactionRoutes(transactions, CategoryCorrectionService(rules, transactions), audit) }, block)

    private fun txn(
        amount: String, category: TransactionCategory = TransactionCategory.FOOD, merchant: String? = "Swiggy",
        type: TransactionType = TransactionType.DEBIT, nature: TransactionNature = TransactionNature.defaultFor(type),
        at: Instant = oct10, owner: UUID = user.id, accountId: UUID? = null, method: PaymentMethod? = PaymentMethod.UPI
    ) = Transaction(userId = owner, type = type, nature = nature, amount = BigDecimal(amount), category = category, merchantName = merchant,
        accountId = accountId, accountLast4 = "4821", paymentMethod = method, upiVpa = merchant?.let { "${it.lowercase()}@hdfcbank" }, transactedAt = at)

    private fun JsonObject.amounts() = dataArray().map { BigDecimal(it.jsonObject["amount"]!!.jsonPrimitive.content).stripTrailingZeros().toPlainString() }

    @Test
    fun `list filters by category, type and account`() = txnTest {
        val hdfc = UUID.randomUUID()
        runBlocking {
            transactions.save(txn("450.00", accountId = hdfc))
            transactions.save(txn("1299.00", TransactionCategory.SHOPPING, "Amazon", accountId = hdfc))
            transactions.save(txn("85000.00", TransactionCategory.OTHER, "Acme Payroll", TransactionType.CREDIT))
            transactions.save(txn("200.00", accountId = UUID.randomUUID()))
        }
        assertEquals(2, getAs(token, "/api/v1/transactions?category=food").second.dataArray().size)
        assertEquals(listOf("85000"), getAs(token, "/api/v1/transactions?type=credit").second.amounts())
        val (_, byAccount) = getAs(token, "/api/v1/transactions?account=$hdfc")
        assertEquals(setOf("450", "1299"), byAccount.amounts().toSet())
    }

    @Test
    fun `a blank merchant search is ignored`() = txnTest {
        runBlocking { transactions.save(txn("450.00")); transactions.save(txn("99.00", merchant = null)) }
        assertEquals(2, getAs(token, "/api/v1/transactions?merchant=").second.dataArray().size)
    }

    @Test
    fun `list items carry type, method and timestamps in lower camelCase`() = txnTest {
        runBlocking { transactions.save(txn("450.00")); transactions.save(txn("500.00", merchant = "ATM", method = null, at = oct10.minusSeconds(60))) }
        val items = getAs(token, "/api/v1/transactions").second.dataArray().map { it.jsonObject }
        assertEquals("upi", items[0].str("paymentMethod"))
        assertEquals("debit", items[0].str("type"))
        assertEquals("food", items[0].str("category"))
        assertEquals(oct10.toString(), items[0].str("transactedAt"))
        assertTrue(items[1].isNull("paymentMethod"))
    }

    @Test
    fun `an invalid account id is a 400`() = txnTest {
        assertEquals(HttpStatusCode.BadRequest, getAs(token, "/api/v1/transactions?account=hdfc").first)
    }

    @Test
    fun `summary nets refunds and leaves out transfers, reversals and income in the range`() = txnTest {
        runBlocking {
            transactions.save(txn("999.00", TransactionCategory.SHOPPING, "Amazon"))
            transactions.save(txn("300.00", TransactionCategory.SHOPPING, "Amazon", TransactionType.REFUND))
            transactions.save(txn("450.00"))
            transactions.save(txn("5000.00", TransactionCategory.TRANSFER, null, TransactionType.TRANSFER))
            transactions.save(txn("249.00", type = TransactionType.REVERSAL))
            transactions.save(txn("85000.00", TransactionCategory.OTHER, "Acme Payroll", TransactionType.CREDIT))
            transactions.save(txn("700.00", at = Instant.parse("2026-09-15T06:30:00Z")))
        }
        val (status, body) = getAs(token, "/api/v1/transactions/summary?from=2026-10-01&to=2026-10-31")
        assertEquals(HttpStatusCode.OK, status)
        val data = body.data()
        assertEquals(0, BigDecimal("1149.00").compareTo(data.money("totalSpending")))
        val byCategory = data["byCategory"]!!.jsonObject
        assertEquals(0, BigDecimal("699.00").compareTo(byCategory.money("shopping")))
        assertEquals(0, BigDecimal("450.00").compareTo(byCategory.money("food")))
        assertEquals("2026-09-30T18:30:00Z", data["period"]!!.jsonObject.str("from"))
    }

    @Test
    fun `summary without a range covers the last 30 days`() = txnTest {
        val now = Instant.now()
        runBlocking {
            transactions.save(txn("450.00", at = now.minus(Duration.ofDays(2))))
            transactions.save(txn("700.00", at = now.minus(Duration.ofDays(45))))
        }
        val data = getAs(token, "/api/v1/transactions/summary").second.data()
        assertEquals(0, BigDecimal("450.00").compareTo(data.money("totalSpending")))
        val period = data["period"]!!.jsonObject
        val days = Duration.between(Instant.parse(period.str("from")), Instant.parse(period.str("to"))).toDays()
        assertEquals(30, days)
    }

    @Test
    fun `summary ignores another user's spending`() = txnTest {
        runBlocking { transactions.save(txn("450.00", owner = UUID.randomUUID())) }
        val data = getAs(token, "/api/v1/transactions/summary?from=2026-10-01&to=2026-10-31").second.data()
        assertEquals(0, BigDecimal.ZERO.compareTo(data.money("totalSpending")))
        assertTrue(data["byCategory"]!!.jsonObject.isEmpty())
    }
    @Test
    fun `summary total is the exact decimal sum of the categories`() = txnTest {
        runBlocking {
            transactions.save(txn("100.10"))
            transactions.save(txn("200.20", TransactionCategory.SHOPPING, "Myntra"))
        }
        val total = getAs(token, "/api/v1/transactions/summary?from=2026-10-01&to=2026-10-31").second.data()["totalSpending"]!!.jsonPrimitive.content
        assertEquals("300.30", total)
    }

    @Test
    fun `PATCH records an audit entry with the before and after category`() = txnTest {
        val saved = runBlocking { transactions.save(txn("120.00", TransactionCategory.OTHER, "Ramu Tea Stall")) }
        val (status, body) = patchAs(token, "/api/v1/transactions/${saved.id}", """{"category":"FOOD","subcategory":"tea"}""")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("user", body.data().str("categorySource"))
        assertEquals("tea", body.data().str("subcategory"))
        val entry = runBlocking { auditRepository.findByEntity("transaction", saved.id) }.single()
        assertEquals(AuditActions.TRANSACTION_UPDATE_CATEGORY, entry.action)
        assertEquals(user.id, entry.userId)
        assertEquals("other", entry.beforeState!!["category"]!!.jsonPrimitive.content)
        assertEquals("", entry.beforeState!!["subcategory"]!!.jsonPrimitive.content)
        assertEquals("food", entry.afterState!!["category"]!!.jsonPrimitive.content)
        assertEquals(true, entry.afterState!!["remembered"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `PATCH without a subcategory audits it as empty and keeps the prior one out`() = txnTest {
        val saved = runBlocking { transactions.save(txn("120.00").copy(subcategory = "delivery")) }
        patchAs(token, "/api/v1/transactions/${saved.id}", """{"category":"shopping","remember":false}""")
        val entry = runBlocking { auditRepository.findByEntity("transaction", saved.id) }.single()
        assertEquals("delivery", entry.beforeState!!["subcategory"]!!.jsonPrimitive.content)
        assertEquals("", entry.afterState!!["subcategory"]!!.jsonPrimitive.content)
        assertEquals(false, entry.afterState!!["remembered"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `PATCH on another user's transaction is a 404 and changes nothing`() = txnTest {
        val theirs = runBlocking { transactions.save(txn("450.00", owner = UUID.randomUUID())) }
        val (status, body) = patchAs(token, "/api/v1/transactions/${theirs.id}", """{"category":"shopping"}""")
        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals("TXN_001", body.errorCode())
        assertEquals(TransactionCategory.FOOD, runBlocking { transactions.findById(theirs.id)!!.category })
        assertTrue(runBlocking { auditRepository.findByEntity("transaction", theirs.id) }.isEmpty())
    }

    @Test
    fun `PATCH on an unknown transaction is a 404`() = txnTest {
        assertEquals(HttpStatusCode.NotFound, patchAs(token, "/api/v1/transactions/${UUID.randomUUID()}", """{"category":"food"}""").first)
    }

    @Test
    fun `PATCH with a malformed id is a 400`() = txnTest {
        assertEquals(HttpStatusCode.BadRequest, patchAs(token, "/api/v1/transactions/12345", """{"category":"food"}""").first)
    }

    @Test
    fun `PATCH with an unknown category is a 400 and changes nothing`() = txnTest {
        val saved = runBlocking { transactions.save(txn("450.00")) }
        assertEquals(HttpStatusCode.BadRequest, patchAs(token, "/api/v1/transactions/${saved.id}", """{"category":"groceries"}""").first)
        assertEquals(TransactionCategory.FOOD, runBlocking { transactions.findById(saved.id)!!.category })
    }

    @Test
    fun `transactions need a session`() = txnTest {
        assertEquals(HttpStatusCode.Unauthorized, getAs(null, "/api/v1/transactions/summary").first)
    }
}
