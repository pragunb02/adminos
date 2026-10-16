package dev.adminos.api.domain.financial

import dev.adminos.api.domain.financial.FinancialCoverageSupport.dataArray
import dev.adminos.api.domain.financial.FinancialCoverageSupport.getAs
import dev.adminos.api.domain.financial.FinancialCoverageSupport.newUser
import dev.adminos.api.domain.financial.FinancialCoverageSupport.routeTest
import dev.adminos.api.domain.financial.FinancialCoverageSupport.str
import dev.adminos.api.domain.financial.FinancialCoverageSupport.tokenFor
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** AccountDiscoveryService and GET /api/v1/accounts (AOS-312). */
class FinancialAccountCoverageTest {

    private val userId = UUID.randomUUID()
    private val accounts = InMemoryAccountRepository()
    private val service = AccountDiscoveryService(accounts, InMemoryTransactionRepository())

    @Test
    fun `a new HDFC card number creates a named savings account`() = runBlocking<Unit> {
        val account = service.discoverAccount(userId, "hdfc ", "4821")!!
        assertEquals("HDFC", account.bankCode)
        assertEquals("HDFC Bank", account.bankName)
        assertEquals("HDFC Bank ****4821", account.accountName)
        assertEquals(AccountType.SAVINGS, account.accountType)
        assertEquals(listOf(account), accounts.findByUserId(userId))
    }

    @Test
    fun `the same bank and last four digits return the existing account`() = runBlocking<Unit> {
        val first = service.discoverAccount(userId, "SBI", "0042")!!
        val again = service.discoverAccount(userId, "sbi", "0042")!!
        assertEquals(first.id, again.id)
        assertEquals(1, accounts.findByUserId(userId).size)
    }

    @Test
    fun `an unlisted bank code keeps the code as its name`() = runBlocking<Unit> {
        val account = service.discoverAccount(userId, "AU", "7788")!!
        assertNull(account.bankName)
        assertEquals("AU ****7788", account.accountName)
    }

    @Test
    fun `a missing bank code is recorded as UNKNOWN`() = runBlocking<Unit> {
        val account = service.discoverAccount(userId, null, "1234")!!
        assertEquals("UNKNOWN", account.bankCode)
        assertEquals("UNKNOWN ****1234", account.accountName)
    }

    @Test
    fun `without the last four digits nothing is discovered`() = runBlocking<Unit> {
        assertNull(service.discoverAccount(userId, "ICICI", null))
        assertNull(service.discoverAccount(userId, "ICICI", "  "))
        assertTrue(accounts.findByUserId(userId).isEmpty())
    }

    @Test
    fun `the same card number under another user is a separate account`() = runBlocking<Unit> {
        val mine = service.discoverAccount(userId, "AXIS", "5566")!!
        val theirs = service.discoverAccount(UUID.randomUUID(), "AXIS", "5566")!!
        assertNotEquals(mine.id, theirs.id)
        assertEquals(listOf(mine.id), service.getUserAccounts(userId).map { it.id })
    }

    @Test
    fun `GET accounts lists only my accounts, newest first, in camelCase`() {
        val user = newUser()
        val older = Account(userId = user.id, bankName = "HDFC Bank", bankCode = "HDFC", accountLast4 = "4821", accountName = "HDFC Bank ****4821",
            isPrimary = true, createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        val newer = Account(userId = user.id, accountType = AccountType.CREDIT_CARD, bankName = "State Bank of India", bankCode = "SBI",
            accountLast4 = "0042", createdAt = Instant.parse("2026-10-01T00:00:00Z"))
        runBlocking {
            accounts.save(older); accounts.save(newer)
            accounts.save(Account(userId = UUID.randomUUID(), bankCode = "ICICI", accountLast4 = "9999"))
        }
        routeTest({ accountRoutes(service) }) {
            val (status, body) = getAs(tokenFor(user), "/api/v1/accounts")
            assertEquals(HttpStatusCode.OK, status)
            val list = body.dataArray().map { it.jsonObject }
            assertEquals(listOf("0042", "4821"), list.map { it.str("accountLast4") })
            assertEquals("credit_card", list[0].str("accountType"))
            assertEquals("HDFC Bank ****4821", list[1].str("accountName"))
            assertEquals(true, list[1]["isPrimary"]!!.jsonPrimitive.boolean)
            assertEquals("INR", list[1].str("currency"))
            assertEquals("2026-09-01T00:00:00Z", list[1].str("createdAt"))
        }
    }

    @Test
    fun `GET accounts needs a session`() = routeTest({ accountRoutes(service) }) {
        assertEquals(HttpStatusCode.Unauthorized, getAs(null, "/api/v1/accounts").first)
    }
}
