package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitServiceTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T06:00:00Z"), ZoneOffset.UTC)
    private val repo = InMemorySplitRepository()
    private val txns = InMemoryTransactionRepository()
    private val service = SplitService(repo, txns, CategorizationService(), UserCalendar(InMemoryUserRepository()), clock)
    private val user = UUID.randomUUID()
    private val analytics = AnalyticsService(InMemoryAnalyticsRepository(txns), clock)

    private val header = "Date,Description,Category,Cost,Currency,Dev P.,Asha R.,Kabir M."
    private fun csv(vararg rows: String) = (listOf(header) + rows).joinToString("\n").toByteArray()

    private val dinner = "2026-10-03,Dinner at Toit,Dining out,4000.00,INR,3000.00,-1500.00,-1500.00"
    private val cab = "2026-10-04,Cab to airport,Taxi,900.00,INR,-300.00,600.00,-300.00"
    private val paidBack = "2026-10-05,Payment,Payment,1500.00,INR,-1500.00,1500.00,0.00"

    private suspend fun bank(
        amount: String, day: Int, type: TransactionType = TransactionType.DEBIT, merchant: String = "Toit Brewpub",
        method: PaymentMethod = PaymentMethod.CARD_CREDIT, category: TransactionCategory = TransactionCategory.FOOD, userId: UUID = user
    ) = txns.save(Transaction(
        userId = userId, sourceType = "statement", type = type, amount = BigDecimal(amount), merchantName = merchant,
        paymentMethod = method, category = category, transactedAt = LocalDate.of(2026, 10, day).atTime(21, 0).atZone(ist).toInstant()
    ))

    private suspend fun entry(state: SplitState? = null, effect: SplitEffect) = repo.findByUser(user, state).single { it.effect == effect }

    @Test
    fun `a bill the owner paid counts only their share, and the rest is owed by friends`() = runBlocking<Unit> {
        val debit = bank("4000.00", 3)
        val r = service.importCsv(user, csv(dinner), "Dev P.")
        assertEquals(1, r.applied)
        assertEquals(BigDecimal("1000.00"), txns.findById(debit.id)?.ownShare)
        assertEquals(BigDecimal("4000.00"), txns.findById(debit.id)?.amount, "the bank amount itself never changes")
        assertEquals(mapOf("INR" to BigDecimal("3000.00")), service.summary(user).balances)
    }

    @Test
    fun `a bill a friend paid becomes the owner's share as a spend, with no personal data in it`() = runBlocking<Unit> {
        service.importCsv(user, csv(cab), "Dev P.")
        val e = entry(SplitState.APPLIED, SplitEffect.OWED_SPEND)
        val spend = txns.findById(e.transactionId!!)!!
        assertEquals(BigDecimal("300.00"), spend.amount)
        assertEquals(TransactionNature.SPEND, spend.nature)
        assertEquals(TransactionCategory.TRANSPORT, spend.category)
        assertEquals(SplitMatcher.SPLITWISE_SOURCE, spend.sourceType)
        assertEquals("Splitwise", spend.merchantName)
        assertEquals("splitwise", spend.categorySource, "never a rules row, so the AI fallback never sees it")
        assertEquals(LocalDate.of(2026, 10, 4), spend.transactedAt.atZone(ist).toLocalDate())
        assertTrue(listOf("Asha", "Kabir", "Cab to airport").none { it in spend.toString() })
    }

    @Test
    fun `an unknown Splitwise category still gets the merchant catalogue's category`() = runBlocking<Unit> {
        service.importCsv(user, csv("2026-10-04,Swiggy order,General,600.00,INR,-300.00,600.00,-300.00"), "Dev P.")
        val spend = txns.findById(entry(SplitState.APPLIED, SplitEffect.OWED_SPEND).transactionId!!)!!
        assertEquals(TransactionCategory.FOOD, spend.category)
    }

    @Test
    fun `money a friend pays back is a settlement, neither income nor spending`() = runBlocking<Unit> {
        val credit = bank("1500.00", 5, TransactionType.CREDIT, merchant = "Asha Rao", method = PaymentMethod.UPI, category = TransactionCategory.OTHER)
        service.importCsv(user, csv(paidBack), "Dev P.")
        val settled = txns.findById(credit.id)!!
        assertEquals(TransactionNature.SETTLEMENT, settled.nature)
        assertEquals(TransactionCategory.TRANSFER, settled.category)
    }

    @Test
    fun `a whole dinner, its payback and a friend's cab add up the same in every view (D-46)`() = runBlocking<Unit> {
        bank("4000.00", 3)
        bank("1500.00", 5, TransactionType.CREDIT, merchant = "Asha Rao", method = PaymentMethod.UPI, category = TransactionCategory.OTHER)
        bank("250.00", 6, merchant = "Swiggy")
        service.importCsv(user, csv(dinner, cab, paidBack), "Dev P.")
        val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16))
        val summary = analytics.summary(user, oct, ist)
        assertEquals(BigDecimal("1550.00"), summary.current.spent, "1000 share + 300 cab + 250 Swiggy")
        assertEquals(0, summary.current.received.signum(), "the payback isn't income")
        assertEquals(summary.current.spent, analytics.categories(user, oct, ist).fold(BigDecimal.ZERO) { a, c -> a + c.spent })
        for (b in Bucket.entries) assertEquals(summary.current.spent, analytics.series(user, oct, b, ist).fold(BigDecimal.ZERO) { a, p -> a + p.money.spent })
        assertEquals(mapOf("INR" to BigDecimal("1200.00")), service.summary(user).balances, "3000 owed − 1500 paid back − 300 owed to Asha")
    }

    @Test
    fun `importing the same export again changes nothing, and an edited split is reported`() = runBlocking<Unit> {
        bank("4000.00", 3)
        service.importCsv(user, csv(dinner, cab), "Dev P.")
        val again = service.importCsv(user, csv(dinner, cab), "Dev P.")
        assertEquals(2, again.alreadyImported)
        assertEquals(0, again.applied + again.suggested + again.unmatched)
        assertEquals(1, txns.findByUserId(user, limit = 100).count { it.sourceType == SplitMatcher.SPLITWISE_SOURCE })
        val edited = service.importCsv(user, csv("2026-10-03,Dinner at Toit,Dining out,4000.00,INR,2000.00,-1000.00,-1000.00"), "Dev P.")
        assertEquals(1, edited.changed)
    }

    @Test
    fun `other currencies and rows that don't touch the owner change nothing`() = runBlocking<Unit> {
        val r = service.importCsv(user, csv(
            "2026-10-03,Hostel,Hotel,90.00,EUR,60.00,-30.00,-30.00",
            "2026-10-03,Their lunch,Dining out,600.00,INR,0.00,300.00,-300.00"
        ), "Dev P.")
        assertEquals(2, r.noEffect)
        assertTrue(txns.findByUserId(user, limit = 100).isEmpty())
        assertEquals(listOf("not in rupees", "doesn't change what you spent"),
            repo.findByUser(user).sortedBy { it.description }.map { it.reason })
    }

    @Test
    fun `an unclear match waits in Review, and confirming it applies the share`() = runBlocking<Unit> {
        val late = bank("4000.00", 4, merchant = "Card Payment", category = TransactionCategory.OTHER)
        val r = service.importCsv(user, csv(dinner), "Dev P.")
        assertEquals(1, r.suggested)
        val e = entry(SplitState.SUGGESTED, SplitEffect.ADJUST_DEBIT)
        assertNull(txns.findById(late.id)?.ownShare, "nothing changes until the owner says so")
        assertEquals(SplitAction.Done, service.confirm(user, e.id))
        assertEquals(BigDecimal("1000.00"), txns.findById(late.id)?.ownShare)
        assertEquals(SplitState.APPLIED, repo.findById(e.id)?.state)
    }

    @Test
    fun `rejecting a suggestion forgets that transaction and offers the next one`() = runBlocking<Unit> {
        val a = bank("4000.00", 4, merchant = "Card Payment", category = TransactionCategory.OTHER)
        val b = bank("4000.00", 4, merchant = "Card Payment", category = TransactionCategory.OTHER)
        service.importCsv(user, csv(dinner), "Dev P.")
        val first = entry(SplitState.SUGGESTED, SplitEffect.ADJUST_DEBIT)
        assertEquals(SplitAction.Done, service.reject(user, first.id))
        val next = repo.findById(first.id)!!
        assertEquals(SplitState.SUGGESTED, next.state)
        assertEquals(setOf(a.id, b.id), setOf(first.transactionId, next.transactionId))
        assertEquals(SplitAction.Done, service.reject(user, first.id))
        assertEquals(SplitState.UNMATCHED, repo.findById(first.id)?.state)
        assertIs<SplitAction.Refused>(service.reject(user, first.id))
    }

    @Test
    fun `the owner can link an unmatched entry by hand, but only to a transaction that fits`() = runBlocking<Unit> {
        service.importCsv(user, csv(dinner), "Dev P.")
        val e = entry(SplitState.UNMATCHED, SplitEffect.ADJUST_DEBIT)
        assertIs<SplitAction.Refused>(service.confirm(user, e.id), "nothing suggested and nothing chosen")
        val wrongAmount = bank("3999.00", 3)
        assertIs<SplitAction.Refused>(service.confirm(user, e.id, wrongAmount.id))
        val someoneElses = bank("4000.00", 3, userId = UUID.randomUUID())
        assertIs<SplitAction.Refused>(service.confirm(user, e.id, someoneElses.id))
        assertIs<SplitAction.Refused>(service.confirm(user, e.id, UUID.randomUUID()))
        // Stored after the import without the listener seeing it, then chosen by hand.
        val right = bank("4000.00", 3, merchant = "Card Payment", category = TransactionCategory.OTHER)
        assertEquals(SplitAction.Done, service.confirm(user, e.id, right.id))
        assertEquals(BigDecimal("1000.00"), txns.findById(right.id)?.ownShare)
        assertIs<SplitAction.Refused>(service.confirm(user, e.id, right.id), "already applied")
    }

    @Test
    fun `a transaction serves one entry`() = runBlocking<Unit> {
        bank("4000.00", 3)
        service.importCsv(user, csv(dinner, "2026-10-03,Dinner again,Dining out,4000.00,INR,2000.00,-2000.00,0.00"), "Dev P.")
        val entries = repo.findByUser(user)
        assertEquals(1, entries.count { it.state == SplitState.APPLIED })
        val other = entries.single { it.state != SplitState.APPLIED }
        assertIs<SplitAction.Refused>(service.confirm(user, other.id, entries.single { it.state == SplitState.APPLIED }.transactionId))
    }

    @Test
    fun `undo takes a share off, puts a settlement back, and removes a friend-paid spend`() = runBlocking<Unit> {
        val debit = bank("4000.00", 3)
        val credit = bank("1500.00", 5, TransactionType.CREDIT, merchant = "Asha Rao", method = PaymentMethod.UPI, category = TransactionCategory.OTHER)
        service.importCsv(user, csv(dinner, cab, paidBack), "Dev P.")
        for (effect in listOf(SplitEffect.ADJUST_DEBIT, SplitEffect.SETTLE_IN, SplitEffect.OWED_SPEND)) {
            assertEquals(SplitAction.Done, service.undo(user, entry(SplitState.APPLIED, effect).id))
        }
        assertNull(txns.findById(debit.id)?.ownShare)
        assertEquals(TransactionNature.INCOME, txns.findById(credit.id)?.nature)
        assertEquals(TransactionCategory.OTHER, txns.findById(credit.id)?.category)
        assertTrue(txns.findByUserId(user, limit = 100).none { it.sourceType == SplitMatcher.SPLITWISE_SOURCE })
        assertEquals(SplitState.UNMATCHED, repo.findById(entry(effect = SplitEffect.ADJUST_DEBIT).id)?.state)
        assertEquals(setOf(debit.id), entry(effect = SplitEffect.ADJUST_DEBIT).rejected, "never matched to it again")
        assertEquals(SplitState.IGNORED, entry(effect = SplitEffect.OWED_SPEND).state)
    }

    @Test
    fun `an ignored entry can be opened again`() = runBlocking<Unit> {
        service.importCsv(user, csv(cab, dinner), "Dev P.")
        val owed = entry(SplitState.APPLIED, SplitEffect.OWED_SPEND)
        service.undo(user, owed.id)
        assertEquals(SplitAction.Done, service.undo(user, owed.id))
        assertEquals(SplitState.APPLIED, repo.findById(owed.id)?.state)
        assertEquals(1, txns.findByUserId(user, limit = 100).count { it.sourceType == SplitMatcher.SPLITWISE_SOURCE })

        val bill = entry(SplitState.UNMATCHED, SplitEffect.ADJUST_DEBIT)
        assertEquals(SplitAction.Done, service.ignore(user, bill.id))
        assertEquals(SplitState.IGNORED, repo.findById(bill.id)?.state)
        assertIs<SplitAction.Refused>(service.ignore(user, bill.id))
        bank("4000.00", 3) // the listener isn't called here; reopening matches it
        assertEquals(SplitAction.Done, service.undo(user, bill.id))
        assertEquals(SplitState.APPLIED, repo.findById(bill.id)?.state)
    }

    @Test
    fun `entries with no effect can't be confirmed, ignored or undone`() = runBlocking<Unit> {
        service.importCsv(user, csv("2026-10-03,Their lunch,Dining out,600.00,INR,0.00,300.00,-300.00"), "Dev P.")
        val e = repo.findByUser(user).single()
        assertIs<SplitAction.Refused>(service.confirm(user, e.id))
        assertIs<SplitAction.Refused>(service.ignore(user, e.id))
        assertIs<SplitAction.Refused>(service.undo(user, e.id))
    }

    @Test
    fun `undoing an import reverses everything it did and lets it be imported afresh`() = runBlocking<Unit> {
        val debit = bank("4000.00", 3)
        val credit = bank("1500.00", 5, TransactionType.CREDIT, merchant = "Asha Rao", method = PaymentMethod.UPI, category = TransactionCategory.OTHER)
        val r = service.importCsv(user, csv(dinner, cab, paidBack), "Dev P.")
        assertEquals(SplitAction.Done, service.undoImport(user, r.importId))
        assertNull(txns.findById(debit.id)?.ownShare)
        assertEquals(TransactionNature.INCOME, txns.findById(credit.id)?.nature)
        assertTrue(txns.findByUserId(user, limit = 100).none { it.sourceType == SplitMatcher.SPLITWISE_SOURCE })
        assertTrue(repo.findByUser(user).isEmpty())
        assertTrue(service.imports(user).isEmpty())
        assertEquals(3, service.importCsv(user, csv(dinner, cab, paidBack), "Dev P.").applied)
    }

    @Test
    fun `a bank record that arrives later is matched then`() = runBlocking<Unit> {
        service.importCsv(user, csv(dinner, paidBack), "Dev P.")
        assertEquals(2, service.summary(user).counts[SplitState.UNMATCHED])
        service.onCreated(bank("4000.00", 4, merchant = "Toit Brewpub"))
        service.onCreated(bank("1500.00", 5, TransactionType.CREDIT, merchant = "Asha Rao", method = PaymentMethod.UPI, category = TransactionCategory.OTHER))
        assertEquals(2, service.summary(user).counts[SplitState.APPLIED])
    }

    @Test
    fun `new transactions that can't be a split are ignored by the listener`() = runBlocking<Unit> {
        service.importCsv(user, csv(dinner), "Dev P.")
        val debit = Transaction(userId = user, sourceType = "sms", type = TransactionType.DEBIT, amount = BigDecimal("4000.00"),
            merchantName = "Toit Brewpub", transactedAt = LocalDate.of(2026, 10, 3).atTime(21, 0).atZone(ist).toInstant())
        service.onCreated(debit.copy(nature = TransactionNature.TRANSFER))
        service.onCreated(debit.copy(currency = "USD"))
        service.onCreated(debit.copy(sourceType = SplitMatcher.SPLITWISE_SOURCE))
        service.onCreated(debit.copy(type = TransactionType.CREDIT, nature = TransactionNature.INCOME)) // stored nowhere: nothing to match
        service.onCreated(debit.copy(amount = BigDecimal("10.00")))
        service.onCreated(debit.copy(type = TransactionType.CREDIT, nature = TransactionNature.REFUND))
        assertEquals(1, service.summary(user).counts[SplitState.UNMATCHED])
    }

    @Test
    fun `another user's entries and imports are not found`() = runBlocking<Unit> {
        val r = service.importCsv(user, csv(dinner, cab), "Dev P.")
        val stranger = UUID.randomUUID()
        val e = repo.findByUser(user).first()
        for (action in listOf<suspend () -> SplitAction>(
            { service.confirm(stranger, e.id) }, { service.reject(stranger, e.id) }, { service.ignore(stranger, e.id) },
            { service.undo(stranger, e.id) }, { service.undoImport(stranger, r.importId) }
        )) assertEquals(SplitAction.NotFound, action())
        assertTrue(service.entries(stranger, null, 100).isEmpty())
        assertTrue(service.summary(stranger).balances.isEmpty())
    }

    @Test
    fun `Review lists entries with their transaction`() = runBlocking<Unit> {
        val debit = bank("4000.00", 3)
        service.importCsv(user, csv(dinner, cab), "Dev P.")
        val views = service.entries(user, SplitState.APPLIED, 10)
        assertEquals(2, views.size)
        assertEquals(debit.id, views.single { it.entry.effect == SplitEffect.ADJUST_DEBIT }.transaction?.id)
        assertEquals(1, service.entries(user, null, 1).size)
    }
}
