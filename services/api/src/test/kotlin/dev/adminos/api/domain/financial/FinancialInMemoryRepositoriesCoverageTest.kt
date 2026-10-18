package dev.adminos.api.domain.financial

import dev.adminos.api.domain.common.CursorCodec
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

/** The in-memory fakes behave like the Postgres repositories they stand in for (AOS-312). */
class FinancialInMemoryRepositoriesCoverageTest {

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val oct10 = Instant.parse("2026-10-10T06:30:00Z")

    // ── Transactions ──

    private val txns = InMemoryTransactionRepository()

    private fun txn(amount: String, at: Instant = oct10, owner: UUID = me, type: TransactionType = TransactionType.DEBIT,
                    category: TransactionCategory = TransactionCategory.FOOD, merchant: String? = "Swiggy", vpa: String? = "swiggy@hdfcbank",
                    accountId: UUID? = null, recurring: Boolean = false, ingestEventId: UUID? = null) =
        Transaction(userId = owner, type = type, amount = BigDecimal(amount), category = category, merchantName = merchant, upiVpa = vpa,
            accountId = accountId, isRecurring = recurring, ingestEventId = ingestEventId, transactedAt = at)

    @Test
    fun `match candidates need the same user, type, exact amount and window`() = runBlocking<Unit> {
        val hit = txns.save(txn("1299.00"))
        txns.save(txn("1299.00", owner = other))
        txns.save(txn("1299.00", type = TransactionType.CREDIT))
        txns.save(txn("1299.50"))
        txns.save(txn("1299.00", at = oct10.plus(Duration.ofDays(3))))
        val found = txns.findMatchCandidates(me, TransactionType.DEBIT, BigDecimal("1299"), oct10.minusSeconds(60), oct10.plusSeconds(60))
        assertEquals(listOf(hit.id), found.map { it.id })
    }

    @Test
    fun `window search honours user, type, time and optional amount bounds`() = runBlocking<Unit> {
        val small = txns.save(txn("100.00"))
        val big = txns.save(txn("900.00"))
        txns.save(txn("500.00", owner = other))
        txns.save(txn("500.00", type = TransactionType.REFUND))
        txns.save(txn("500.00", at = oct10.minus(Duration.ofDays(30))))
        val from = oct10.minus(Duration.ofDays(1)); val to = oct10.plus(Duration.ofDays(1))
        assertEquals(setOf(small.id, big.id), txns.findInWindow(me, TransactionType.DEBIT, from, to).map { it.id }.toSet())
        assertEquals(listOf(big.id), txns.findInWindow(me, TransactionType.DEBIT, from, to, minAmount = BigDecimal("500")).map { it.id })
        assertEquals(listOf(small.id), txns.findInWindow(me, TransactionType.DEBIT, from, to, maxAmount = BigDecimal("500")).map { it.id })
    }

    @Test
    fun `payee search matches a UPI ID or merchant name case-insensitively for one user`() = runBlocking<Unit> {
        val byVpa = txns.save(txn("80.00", merchant = "Ramu", vpa = " Ramu@OKAXIS "))
        val byName = txns.save(txn("60.00", merchant = " Ramu Tea Stall ", vpa = null))
        txns.save(txn("80.00", owner = other, vpa = "ramu@okaxis"))
        assertEquals(listOf(byVpa.id), txns.findByPayee(me, RuleMatch.VPA, "ramu@okaxis").map { it.id })
        assertEquals(listOf(byName.id), txns.findByPayee(me, RuleMatch.MERCHANT, "ramu tea stall").map { it.id })
    }

    @Test
    fun `lookup by intake event and delete`() = runBlocking<Unit> {
        val event = UUID.randomUUID()
        val saved = txns.save(txn("450.00", ingestEventId = event))
        assertEquals(saved, txns.findByIngestEventId(event))
        assertNull(txns.findByIngestEventId(UUID.randomUUID()))
        txns.delete(saved.id)
        assertNull(txns.findById(saved.id))
    }

    @Test
    fun `list and count apply every filter together`() = runBlocking<Unit> {
        val hdfc = UUID.randomUUID()
        val hit = txns.save(txn("649.00", category = TransactionCategory.SUBSCRIPTION, merchant = "Netflix", accountId = hdfc, recurring = true))
        txns.save(txn("649.00", category = TransactionCategory.SUBSCRIPTION, merchant = "Netflix", accountId = hdfc, recurring = false))
        txns.save(txn("649.00", category = TransactionCategory.SUBSCRIPTION, merchant = "Netflix", accountId = UUID.randomUUID(), recurring = true))
        txns.save(txn("649.00", category = TransactionCategory.SUBSCRIPTION, merchant = "Hotstar", accountId = hdfc, recurring = true))
        txns.save(txn("649.00", category = TransactionCategory.SUBSCRIPTION, merchant = null, accountId = hdfc, recurring = true))
        txns.save(txn("649.00", category = TransactionCategory.ENTERTAINMENT, merchant = "Netflix", accountId = hdfc, recurring = true))
        txns.save(txn("649.00", type = TransactionType.CREDIT, category = TransactionCategory.SUBSCRIPTION, merchant = "Netflix", accountId = hdfc, recurring = true))
        txns.save(txn("649.00", at = oct10.minus(Duration.ofDays(40)), category = TransactionCategory.SUBSCRIPTION, merchant = "Netflix", accountId = hdfc, recurring = true))
        txns.save(txn("649.00", at = oct10.plus(Duration.ofDays(40)), category = TransactionCategory.SUBSCRIPTION, merchant = "Netflix", accountId = hdfc, recurring = true))
        val from = oct10.minus(Duration.ofDays(1)); val to = oct10.plus(Duration.ofDays(1))
        val list = txns.findByUserId(me, from, to, TransactionCategory.SUBSCRIPTION, TransactionType.DEBIT, hdfc, true, "NETF", null, 20)
        assertEquals(listOf(hit.id), list.map { it.id })
        assertEquals(1L, txns.countByUserId(me, from, to, TransactionCategory.SUBSCRIPTION, TransactionType.DEBIT, hdfc, true, "netf"))
        assertEquals(9L, txns.countByUserId(me))
        assertEquals(9, txns.findByUserId(me, limit = 100).size)
    }

    @Test
    fun `the default page holds 20 transactions, newest first, and a cursor continues it`() = runBlocking<Unit> {
        (1..25).forEach { txns.save(txn("10.00", at = oct10.minus(Duration.ofHours(it.toLong())))) }
        val page = txns.findByUserId(me)
        assertEquals(20, page.size)
        assertEquals(oct10.minus(Duration.ofHours(1)), page.first().transactedAt)
        val rest = txns.findByUserId(me, cursor = CursorCodec.encode(page.last().transactedAt.toString()))
        assertEquals(5, rest.size)
    }

    @Test
    fun `update stamps updatedAt and replaces the stored transaction`() = runBlocking<Unit> {
        val saved = txns.save(txn("450.00").copy(updatedAt = Instant.EPOCH))
        val updated = txns.update(saved.copy(category = TransactionCategory.SHOPPING))
        assertEquals(TransactionCategory.SHOPPING, txns.findById(saved.id)!!.category)
        assertTrue(updated.updatedAt.isAfter(Instant.EPOCH))
    }

    @Test
    fun `spending since a date counts only my spend nature, oldest first`() = runBlocking<Unit> {
        val late = txns.save(txn("300.00", at = oct10))
        val early = txns.save(txn("200.00", at = oct10.minus(Duration.ofDays(2))))
        txns.save(txn("100.00", at = oct10.minus(Duration.ofDays(20))))
        txns.save(txn("5000.00", type = TransactionType.TRANSFER))
        txns.save(txn("99.00", owner = other))
        assertEquals(listOf(early.id, late.id), txns.findDebitsByUserIdSince(me, oct10.minus(Duration.ofDays(5))).map { it.id })
    }

    @Test
    fun `spending by category nets refunds and ignores other users and dates outside the range`() = runBlocking<Unit> {
        txns.save(txn("999.00", category = TransactionCategory.SHOPPING))
        txns.save(txn("300.00", type = TransactionType.REFUND, category = TransactionCategory.SHOPPING))
        txns.save(txn("450.00", owner = other))
        txns.save(txn("450.00", at = oct10.minus(Duration.ofDays(10))))
        txns.save(txn("85000.00", type = TransactionType.CREDIT, category = TransactionCategory.OTHER))
        val result = txns.spendingByCategory(me, oct10.minus(Duration.ofDays(1)), oct10.plus(Duration.ofDays(1)))
        assertEquals(mapOf(TransactionCategory.SHOPPING to BigDecimal("699.00")), result)
    }

    @Test
    fun `category average is the mean of recent spending in that category`() = runBlocking<Unit> {
        val now = Instant.now()
        txns.save(txn("300.00", at = now.minus(Duration.ofDays(1))))
        txns.save(txn("200.00", at = now.minus(Duration.ofDays(2))))
        txns.save(txn("1000.00", at = now.minus(Duration.ofDays(2)), category = TransactionCategory.SHOPPING))
        txns.save(txn("900.00", at = now.minus(Duration.ofDays(60))))
        txns.save(txn("900.00", at = now.minus(Duration.ofDays(1)), owner = other))
        txns.save(txn("900.00", at = now.minus(Duration.ofDays(1)), type = TransactionType.REFUND))
        assertEquals(BigDecimal("250.00"), txns.getCategoryAverage(me, "food", 30))
    }

    @Test
    fun `an unknown category averages over all recent spending`() = runBlocking<Unit> {
        val now = Instant.now()
        txns.save(txn("300.00", at = now.minus(Duration.ofDays(1))))
        txns.save(txn("1000.00", at = now.minus(Duration.ofDays(2)), category = TransactionCategory.SHOPPING))
        assertEquals(BigDecimal("650.00"), txns.getCategoryAverage(me, "groceries", 30))
    }

    @Test
    fun `category average with no spending is zero`() = runBlocking<Unit> {
        assertEquals(BigDecimal.ZERO, txns.getCategoryAverage(me, "food", 30))
    }

    @Test
    fun `recent transactions by merchant are case-insensitive, newest first, within the hours given`() = runBlocking<Unit> {
        val now = Instant.now()
        val older = txns.save(txn("100.00", at = now.minus(Duration.ofHours(5)), merchant = "SWIGGY"))
        val newer = txns.save(txn("200.00", at = now.minus(Duration.ofHours(1)), merchant = "Swiggy"))
        txns.save(txn("300.00", at = now.minus(Duration.ofHours(30)), merchant = "Swiggy"))
        txns.save(txn("400.00", at = now.minus(Duration.ofHours(1)), merchant = "Zomato"))
        txns.save(txn("500.00", at = now.minus(Duration.ofHours(1)), merchant = null))
        txns.save(txn("600.00", at = now.minus(Duration.ofHours(1)), merchant = "Swiggy", owner = other))
        assertEquals(listOf(newer.id, older.id), txns.getRecentByMerchant(me, "swiggy", 24).map { it.id })
    }

    // ── Accounts ──

    private val accounts = InMemoryAccountRepository()

    @Test
    fun `account lookup needs the same user, bank code and last four digits`() = runBlocking<Unit> {
        val mine = accounts.save(Account(userId = me, bankCode = "HDFC", accountLast4 = "4821"))
        accounts.save(Account(userId = other, bankCode = "HDFC", accountLast4 = "4821"))
        assertEquals(mine, accounts.findByUserAndBankAndLast4(me, "HDFC", "4821"))
        assertNull(accounts.findByUserAndBankAndLast4(me, "SBI", "4821"))
        assertNull(accounts.findByUserAndBankAndLast4(me, "HDFC", "0000"))
        assertEquals(mine, accounts.findById(mine.id))
    }

    @Test
    fun `account update stamps updatedAt`() = runBlocking<Unit> {
        val saved = accounts.save(Account(userId = me, bankCode = "SBI", accountLast4 = "0042", updatedAt = Instant.EPOCH))
        val updated = accounts.update(saved.copy(isPrimary = true))
        assertTrue(accounts.findById(saved.id)!!.isPrimary)
        assertTrue(updated.updatedAt.isAfter(Instant.EPOCH))
    }

    // ── Subscriptions ──

    private val subs = InMemorySubscriptionRepository()

    private fun sub(name: String?, cycle: BillingCycle = BillingCycle.MONTHLY, owner: UUID = me, flagged: Boolean = false,
                    status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
        Subscription(userId = owner, name = name ?: "Unknown", merchantName = name, amount = BigDecimal("649"), billingCycle = cycle,
            isFlagged = flagged, status = status)

    @Test
    fun `subscription lookup by merchant and cycle is case-insensitive and per user`() = runBlocking<Unit> {
        val mine = subs.save(sub("Netflix"))
        subs.save(sub("Netflix", owner = other))
        subs.save(sub("Netflix", BillingCycle.YEARLY, owner = other))
        subs.save(sub(null))
        assertEquals(mine, subs.findByUserAndMerchantAndCycle(me, "NETFLIX", BillingCycle.MONTHLY))
        assertNull(subs.findByUserAndMerchantAndCycle(me, "netflix", BillingCycle.YEARLY))
        assertNull(subs.findByUserAndMerchantAndCycle(me, "spotify", BillingCycle.MONTHLY))
    }

    @Test
    fun `subscription counts filter by status and flag`() = runBlocking<Unit> {
        subs.save(sub("Netflix", flagged = true))
        subs.save(sub("Spotify"))
        subs.save(sub("Hotstar", flagged = true, status = SubscriptionStatus.CANCELLED))
        assertEquals(3L, subs.countByUserId(me))
        assertEquals(2L, subs.countByUserId(me, flagged = true))
        assertEquals(1L, subs.countByUserId(me, SubscriptionStatus.ACTIVE, flagged = true))
        assertEquals(1, subs.findByUserId(me, SubscriptionStatus.CANCELLED, flagged = true).size)
    }

    @Test
    fun `subscription update stamps updatedAt`() = runBlocking<Unit> {
        val saved = subs.save(sub("Netflix").copy(updatedAt = Instant.EPOCH))
        val updated = subs.update(saved.copy(status = SubscriptionStatus.PAUSED))
        assertEquals(SubscriptionStatus.PAUSED, subs.findById(saved.id)!!.status)
        assertTrue(updated.updatedAt.isAfter(Instant.EPOCH))
    }

    // ── Bills ──

    private val today = LocalDate.of(2026, 10, 16)
    private val bills = InMemoryBillRepository()

    private fun bill(biller: String, due: LocalDate, status: BillStatus = BillStatus.UPCOMING, owner: UUID = me) =
        Bill(userId = owner, billerName = biller, amount = BigDecimal("1499.00"), dueDate = due, status = status)

    @Test
    fun `pending bills are my upcoming, due and overdue ones, earliest first`() = runBlocking<Unit> {
        val overdue = bills.save(bill("BESCOM", today.minusDays(3), BillStatus.OVERDUE))
        val due = bills.save(bill("SBI Card", today, BillStatus.DUE))
        val upcoming = bills.save(bill("Tata Power", today.plusDays(5)))
        bills.save(bill("Airtel", today.plusDays(1), BillStatus.PAID))
        bills.save(bill("Jio", today.plusDays(1), BillStatus.CANCELLED))
        bills.save(bill("ICICI Card", today.plusDays(1), owner = other))
        assertEquals(listOf(overdue.id, due.id, upcoming.id), bills.findPendingBills(me).map { it.id })
    }

    @Test
    fun `upcoming bills across all users are unpaid ones due from today to the cutoff`() = runBlocking<Unit> {
        val mine = bills.save(bill("Tata Power", today))
        val theirs = bills.save(bill("ICICI Card", today.plusDays(7), BillStatus.DUE, owner = other))
        bills.save(bill("LIC", today.plusDays(8)))
        bills.save(bill("BESCOM", today.minusDays(1)))
        bills.save(bill("Airtel", today.plusDays(2), BillStatus.PAID))
        assertEquals(setOf(mine.id, theirs.id), bills.findUpcoming(today, 7).map { it.id }.toSet())
    }

    @Test
    fun `my upcoming bills default to the next 30 days`() = runBlocking<Unit> {
        val soon = bills.save(bill("Tata Power", today.plusDays(30)))
        bills.save(bill("LIC", today.plusDays(31)))
        bills.save(bill("ICICI Card", today.plusDays(3), owner = other))
        bills.save(bill("SBI Card", today.plusDays(3), BillStatus.OVERDUE))
        assertEquals(listOf(soon.id), bills.findUpcoming(me, today).map { it.id })
    }

    @Test
    fun `overdue bills are unpaid bills of any user due before today`() = runBlocking<Unit> {
        val mine = bills.save(bill("BESCOM", today.minusDays(1)))
        val theirs = bills.save(bill("ICICI Card", today.minusDays(9), BillStatus.DUE, owner = other))
        bills.save(bill("Tata Power", today))
        bills.save(bill("Airtel", today.minusDays(4), BillStatus.PAID))
        bills.save(bill("SBI Card", today.minusDays(4), BillStatus.OVERDUE))
        assertEquals(setOf(mine.id, theirs.id), bills.findOverdue(today).map { it.id }.toSet())
    }

    @Test
    fun `bill counts filter by status and the list pages by due date`() = runBlocking<Unit> {
        bills.save(bill("Tata Power", today.plusDays(1)))
        bills.save(bill("SBI Card", today.plusDays(2), BillStatus.PAID))
        bills.save(bill("Airtel", today.plusDays(3)))
        assertEquals(3L, bills.countByUserId(me))
        assertEquals(1L, bills.countByUserId(me, BillStatus.PAID))
        assertEquals(listOf("Tata Power", "SBI Card", "Airtel"), bills.findByUserId(me).map { it.billerName })
        val next = bills.findByUserId(me, cursor = CursorCodec.encode(today.plusDays(1).toString()))
        assertEquals(listOf("SBI Card", "Airtel"), next.map { it.billerName })
    }

    @Test
    fun `bill update stamps updatedAt`() = runBlocking<Unit> {
        val saved = bills.save(bill("Tata Power", today).copy(updatedAt = Instant.EPOCH))
        val updated = bills.update(saved.copy(status = BillStatus.PAID))
        assertEquals(BillStatus.PAID, bills.findById(saved.id)!!.status)
        assertTrue(updated.updatedAt.isAfter(Instant.EPOCH))
    }

    @Test
    fun `the bill repository judges dates by the day it is given, never the clock`() = runBlocking<Unit> {
        val longAgo = LocalDate.of(2001, 1, 1)
        val due = bills.save(bill("Tata Power", longAgo.plusDays(1)))
        assertEquals(listOf(due.id), bills.findUpcoming(me, longAgo, 30).map { it.id })
        assertEquals(listOf(due.id), bills.findOverdue(longAgo.plusDays(2)).map { it.id })
        assertTrue(bills.findOverdue(longAgo.plusDays(1)).isEmpty())
    }
}
