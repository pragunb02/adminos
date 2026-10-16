package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.domain.financial.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinancialRepositoriesIntegrationTest {

    private val transactions = ExposedTransactionRepository()
    private val accounts = ExposedAccountRepository()
    private val subscriptions = ExposedSubscriptionRepository()
    private val bills = ExposedBillRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun txn(userId: UUID, type: TransactionType = TransactionType.DEBIT, category: TransactionCategory = TransactionCategory.FOOD,
                    amount: String = "249.50", merchant: String = "Swiggy", at: Instant = Instant.now()) =
        Transaction(
            userId = userId, type = type, amount = BigDecimal(amount), merchantName = merchant, merchantRaw = "SWIGGY*ORDER",
            paymentMethod = PaymentMethod.UPI, upiVpa = "swiggy@icici", category = category, accountLast4 = "1234",
            metadata = mapOf("source_sms_id" to "abc"), transactedAt = at.truncatedTo(ChronoUnit.MILLIS)
        )

    // ── Transactions ──

    @Test
    fun `transaction round-trips all fields`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val t = transactions.save(txn(userId))

        val found = assertNotNull(transactions.findById(t.id))
        assertEquals(TransactionType.DEBIT, found.type)
        assertEquals(0, BigDecimal("249.50").compareTo(found.amount))
        assertEquals("Swiggy", found.merchantName)
        assertEquals(PaymentMethod.UPI, found.paymentMethod)
        assertEquals("swiggy@icici", found.upiVpa)
        assertEquals(TransactionCategory.FOOD, found.category)
        assertEquals("abc", found.metadata?.get("source_sms_id"))
        assertEquals(t.transactedAt, found.transactedAt.truncatedTo(ChronoUnit.MILLIS))

        transactions.update(found.copy(category = TransactionCategory.ENTERTAINMENT, categorySource = "user"))
        assertEquals(TransactionCategory.ENTERTAINMENT, transactions.findById(t.id)?.category)
    }

    @Test
    fun `every transaction type, category, payment method and status persists`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        for (type in TransactionType.entries) assertEquals(type, transactions.findById(transactions.save(txn(userId, type = type)).id)?.type)
        for (cat in TransactionCategory.entries) assertEquals(cat, transactions.findById(transactions.save(txn(userId, category = cat)).id)?.category)
        for (pm in PaymentMethod.entries) assertEquals(pm, transactions.findById(transactions.save(txn(userId).copy(paymentMethod = pm)).id)?.paymentMethod)
        for (st in TransactionStatus.entries) assertEquals(st, transactions.findById(transactions.save(txn(userId).copy(status = st)).id)?.status)
    }

    @Test
    fun `queries filter by user, time, type and merchant`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val now = Instant.now()
        transactions.save(txn(userId, amount = "100.00", category = TransactionCategory.FOOD, at = now.minus(1, ChronoUnit.DAYS)))
        transactions.save(txn(userId, amount = "300.00", category = TransactionCategory.FOOD, at = now.minus(2, ChronoUnit.DAYS)))
        transactions.save(txn(userId, amount = "50.00", category = TransactionCategory.TRANSPORT, merchant = "Uber", at = now.minus(3, ChronoUnit.DAYS)))
        transactions.save(txn(userId, type = TransactionType.CREDIT, amount = "5000.00", category = TransactionCategory.TRANSFER, merchant = "Salary"))
        transactions.save(txn(PostgresTestDatabase.newUser(), amount = "999.00")) // another user: must not leak

        assertEquals(4, transactions.findByUserId(userId, limit = 50).size)
        assertEquals(4L, transactions.countByUserId(userId))
        assertEquals(1, transactions.findByUserId(userId, type = TransactionType.CREDIT).size)
        assertEquals(3, transactions.findDebitsByUserIdSince(userId, now.minus(7, ChronoUnit.DAYS)).size)
        assertEquals(1, transactions.findByUserId(userId, merchantSearch = "uber").size)

        val byCategory = transactions.spendingByCategory(userId, now.minus(7, ChronoUnit.DAYS), now.plus(1, ChronoUnit.HOURS))
        assertEquals(0, BigDecimal("400.00").compareTo(byCategory[TransactionCategory.FOOD]))
        assertEquals(0, BigDecimal("50.00").compareTo(byCategory[TransactionCategory.TRANSPORT]))
        assertTrue(TransactionCategory.TRANSFER !in byCategory, "credits are not spending")

        assertEquals(2, transactions.getRecentByMerchant(userId, "Swiggy", hours = 72).size)
    }

    @Test
    fun `cursor pagination pages through transactions without overlap`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val now = Instant.now()
        (1..5).forEach { transactions.save(txn(userId, at = now.minus(it.toLong(), ChronoUnit.HOURS))) }

        val page1 = transactions.findByUserId(userId, limit = 2)
        val cursor = CursorCodec.encode(page1.last().transactedAt.toString()) // what the route returns
        val page2 = transactions.findByUserId(userId, cursor = cursor, limit = 2)
        val page3 = transactions.findByUserId(userId, cursor = CursorCodec.encode(page2.last().transactedAt.toString()), limit = 2)

        assertEquals(listOf(2, 2, 1), listOf(page1.size, page2.size, page3.size))
        assertEquals(5, (page1 + page2 + page3).map { it.id }.toSet().size)
    }

    // ── Accounts ──

    @Test
    fun `account round-trips and is found by bank and last4`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        for (type in AccountType.entries) {
            val a = accounts.save(Account(userId = userId, accountType = type, bankName = "HDFC Bank", bankCode = "hdfc-$type", accountLast4 = "4321", metadata = mapOf("ifsc" to "HDFC0000001")))
            val found = assertNotNull(accounts.findById(a.id))
            assertEquals(type, found.accountType)
            assertEquals("HDFC0000001", found.metadata?.get("ifsc"))
            assertEquals(a.id, accounts.findByUserAndBankAndLast4(userId, "hdfc-$type", "4321")?.id)
        }
        assertEquals(AccountType.entries.size, accounts.findByUserId(userId).size)
    }

    // ── Subscriptions ──

    @Test
    fun `subscription round-trips including transaction ids`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val t1 = transactions.save(txn(userId, merchant = "Netflix")).id
        val t2 = transactions.save(txn(userId, merchant = "Netflix")).id
        val s = subscriptions.save(
            Subscription(
                userId = userId, name = "Netflix", merchantName = "Netflix", category = SubscriptionCategory.ENTERTAINMENT,
                amount = BigDecimal("649.00"), billingCycle = BillingCycle.MONTHLY, nextBillingDate = LocalDate.now().plusDays(10),
                wasteScore = BigDecimal("0.75"), transactionIds = listOf(t1, t2), metadata = mapOf("plan" to "standard")
            )
        )
        val found = assertNotNull(subscriptions.findById(s.id))
        assertEquals(listOf(t1, t2), found.transactionIds)
        assertEquals(0, BigDecimal("0.75").compareTo(found.wasteScore))
        assertEquals(s.nextBillingDate, found.nextBillingDate)
        assertEquals(s.id, subscriptions.findByUserAndMerchantAndCycle(userId, "Netflix", BillingCycle.MONTHLY)?.id)

        subscriptions.update(found.copy(status = SubscriptionStatus.CANCELLED, isFlagged = true, flaggedReason = "unused"))
        assertEquals(SubscriptionStatus.CANCELLED, subscriptions.findById(s.id)?.status)
        assertEquals(1L, subscriptions.countByUserId(userId, SubscriptionStatus.CANCELLED))
    }

    @Test
    fun `every subscription enum value persists`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        var i = 0
        fun sub(cycle: BillingCycle = BillingCycle.MONTHLY) =
            Subscription(userId = userId, name = "Sub", merchantName = "m-${i++}", amount = BigDecimal("99.00"), billingCycle = cycle)
        for (c in BillingCycle.entries) assertEquals(c, subscriptions.findById(subscriptions.save(sub(c)).id)?.billingCycle)
        for (c in SubscriptionCategory.entries) assertEquals(c, subscriptions.findById(subscriptions.save(sub().copy(category = c)).id)?.category)
        for (s in SubscriptionStatus.entries) assertEquals(s, subscriptions.findById(subscriptions.save(sub().copy(status = s)).id)?.status)
        for (u in UsageStatus.entries) assertEquals(u, subscriptions.findById(subscriptions.save(sub().copy(usageStatus = u)).id)?.usageStatus)
    }

    // ── Bills ──

    @Test
    fun `bill round-trips and appears in upcoming and pending`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val b = bills.save(
            Bill(userId = userId, billType = BillType.CREDIT_CARD, billerName = "HDFC Credit Card", amount = BigDecimal("12500.00"),
                 minimumDue = BigDecimal("625.00"), dueDate = LocalDate.now().plusDays(5), metadata = mapOf("card" to "1234"))
        )
        val found = assertNotNull(bills.findById(b.id))
        assertEquals(BillType.CREDIT_CARD, found.billType)
        assertEquals(b.dueDate, found.dueDate)
        assertEquals(0, BigDecimal("625.00").compareTo(found.minimumDue))
        assertTrue(bills.findUpcoming(userId, withinDays = 30).any { it.id == b.id })
        assertTrue(bills.findPendingBills(userId).any { it.id == b.id })

        val payment = transactions.save(txn(userId, amount = "12500.00", merchant = "HDFC Credit Card"))
        bills.update(found.copy(status = BillStatus.PAID, paidAmount = BigDecimal("12500.00"), paidAt = Instant.now(), paymentTxnId = payment.id))
        val paid = assertNotNull(bills.findById(b.id))
        assertEquals(BillStatus.PAID, paid.status)
        assertEquals(payment.id, paid.paymentTxnId)
    }

    @Test
    fun `every bill type and status persists and overdue bills are found`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        fun bill() = Bill(userId = userId, billerName = "Biller", amount = BigDecimal("100.00"), dueDate = LocalDate.now().plusDays(3))
        for (t in BillType.entries) assertEquals(t, bills.findById(bills.save(bill().copy(billType = t)).id)?.billType)
        for (s in BillStatus.entries) assertEquals(s, bills.findById(bills.save(bill().copy(status = s)).id)?.status)

        val late = bills.save(bill().copy(dueDate = LocalDate.now().minusDays(2), status = BillStatus.UPCOMING))
        assertTrue(bills.findOverdue().any { it.id == late.id })
    }

    @Test
    fun `bills sharing a due date page through without gaps or repeats (AOS-612)`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val day = LocalDate.of(2026, 11, 5)
        val saved = (1..5).map { i -> bills.save(Bill(userId = user, billerName = "Biller $i", amount = BigDecimal("100.00"), dueDate = if (i == 5) day.plusDays(1) else day)) }
        val seen = mutableListOf<UUID>()
        var cursor: String? = null
        do {
            val page = bills.findByUserId(user, null, null, null, cursor, 2)
            seen += page.map { it.id }
            cursor = page.lastOrNull()?.takeIf { page.size == 2 }?.let { CursorCodec.encode(billCursorKey(it)) }
        } while (cursor != null)
        assertEquals(saved.map { it.id }.toSet(), seen.toSet())
        assertEquals(seen.size, seen.toSet().size)
        assertEquals(day.plusDays(1), bills.findById(seen.last())!!.dueDate)
    }

    @Test
    fun `a date-only bill cursor from before AOS-612 continues after that day`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val day = LocalDate.of(2026, 11, 7)
        bills.save(Bill(userId = user, billerName = "Same day", amount = BigDecimal.TEN, dueDate = day))
        val next = bills.save(Bill(userId = user, billerName = "Next day", amount = BigDecimal.TEN, dueDate = day.plusDays(1)))
        assertEquals(listOf(next.id), bills.findByUserId(user, null, null, null, CursorCodec.encode(day.toString()), 10).map { it.id })
    }

    @Test
    fun `transactions sharing a timestamp page through without gaps, newest first, and filter by nature (AOS-307)`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val at = Instant.parse("2026-10-06T18:30:00Z")
        val saved = (1..5).map { transactions.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("10.00"), merchantName = "Row $it", transactedAt = at)) }
        transactions.save(Transaction(userId = user, type = TransactionType.CREDIT, nature = TransactionNature.REFUND, amount = BigDecimal("5.00"), merchantName = "Back", transactedAt = at.plusSeconds(60)))
        val seen = mutableListOf<UUID>()
        var cursor: String? = null
        do {
            val page = transactions.findByUserId(user, cursor = cursor, limit = 2, nature = TransactionNature.SPEND)
            seen += page.map { it.id }
            cursor = page.lastOrNull()?.takeIf { page.size == 2 }?.let { CursorCodec.encode(transactionCursorKey(it)) }
        } while (cursor != null)
        assertEquals(saved.map { it.id }.toSet(), seen.toSet())
        assertEquals(5, seen.size)
        assertEquals(1L, transactions.countByUserId(user, nature = TransactionNature.REFUND))
        assertEquals(6L, transactions.countByUserId(user))
        // a time-only cursor from before AOS-307 still continues after that instant
        assertEquals(emptyList(), transactions.findByUserId(user, cursor = CursorCodec.encode(at.toString()), limit = 10, nature = TransactionNature.SPEND))
    }
}
