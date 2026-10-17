package dev.adminos.api.domain.financial.grouping

import dev.adminos.api.domain.analytics.AliasedAnalyticsRepository
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.InMemoryPriceRiseRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.SubscriptionDetectorService
import dev.adminos.api.domain.financial.SubscriptionStatus
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-709: proposals from real payments, the answers, and the aliases the detector and analytics read. */
class MerchantGroupingServiceTest {
    private val now = Instant.parse("2026-10-16T06:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = UUID.randomUUID()
    private val transactions = InMemoryTransactionRepository()
    private val aliases = InMemoryMerchantAliasRepository()
    private val audits = InMemoryAuditRepository()
    private val calendar = UserCalendar(InMemoryUserRepository(), clock)
    private val service = MerchantGroupingService(transactions, aliases, calendar, AuditService(audits, CoroutineScope(Dispatchers.Unconfined)), clock = clock)

    private suspend fun pay(merchant: String, daysAgo: Long, amount: String = "649.00", owner: UUID = userId, upi: String? = null,
                            method: PaymentMethod = PaymentMethod.CARD_CREDIT) =
        transactions.save(Transaction(userId = owner, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant,
            upiVpa = upi, paymentMethod = method, transactedAt = now.minusSeconds(daysAgo * 86_400)))

    @Test
    fun `look-alike names are proposed as one group, named after the catalogue brand`() = runBlocking<Unit> {
        pay("NETFLIX.COM", 65); pay("NETFLIX.COM", 35); pay("Netflixcom", 5); pay("Netflix", 95); pay("Swiggy", 3, "350.00")
        val p = service.proposals(userId).single()
        assertEquals("Netflix", p.canonical.name)
        assertEquals(listOf("NETFLIX.COM" to 2, "Netflixcom" to 1), p.members.map { it.name to it.payments })
        assertTrue(MatchEvidence.SAME_CLEAN_NAME in p.evidence && MatchEvidence.KNOWN_BRAND in p.evidence)
        assertEquals(MerchantGroupingService.idOf(userId, listOf("netflix", "netflix.com", "netflixcom")), p.id)
    }

    @Test
    fun `without a brand, the most used name is kept`() = runBlocking<Unit> {
        pay("Toonverse", 40); pay("Toonverse", 10); pay("TOONVERSE.IN", 5); pay("Razorpay Toonverse", 70)
        val p = service.proposals(userId).single()
        assertEquals("Toonverse", p.canonical.name)
        assertEquals(setOf("TOONVERSE.IN", "Razorpay Toonverse"), p.members.map { it.name }.toSet())
    }

    @Test
    fun `confirming makes each name an alias, so nothing is proposed again and the detector sees one subscription`() = runBlocking<Unit> {
        listOf(95L, 65L).forEach { pay("Spotifyindia", it, "119.00") }
        listOf(35L, 5L).forEach { pay("Spotify Ab", it, "119.00") }
        val subscriptions = InMemorySubscriptionRepository()
        val detector = SubscriptionDetectorService(transactions, subscriptions, calendar, InMemoryPriceRiseRepository(), aliases)
        assertEquals(2, detector.detectSubscriptions(userId).size, "two subscriptions before the merge")

        val p = service.proposals(userId).single()
        val merged = assertIs<MergeDecision.Merged>(service.confirm(userId, p.id))
        assertEquals(p, merged.proposal)
        assertEquals(emptyList(), service.proposals(userId))
        assertEquals(setOf("spotifyindia"), aliases.aliases(userId).map { it.aliasKey }.toSet(), "the shorter of two equally used names is kept")

        val after = detector.detectSubscriptions(userId).single()
        assertEquals(4, after.transactionIds.size)
        assertEquals("spotify ab", after.merchantName)
        val ended = subscriptions.findByUserId(userId, status = SubscriptionStatus.CANCELLED).single()
        assertEquals("merge", ended.metadata?.get("endedBy"))
        assertEquals("Merged into Spotify Ab", ended.metadata?.get("endedReason"))
        assertEquals(after.id.toString(), ended.metadata?.get("mergedInto"))
        assertEquals(1, detector.detectSubscriptions(userId).size, "a re-run changes nothing")
    }

    @Test
    fun `rejecting remembers every pair, and a stale or foreign proposal is not found`() = runBlocking<Unit> {
        pay("Kukufm", 10); pay("Kuku Fm", 20); pay("KUKU FM INDIA", 30)
        val p = service.proposals(userId).single()
        assertIs<MergeDecision.Kept>(service.reject(userId, p.id))
        assertEquals(3, aliases.rejections(userId).size)
        assertEquals(emptyList(), service.proposals(userId))
        assertEquals(MergeDecision.NotFound, service.confirm(userId, p.id), "already answered")
        assertEquals(MergeDecision.NotFound, service.reject(UUID.randomUUID(), p.id), "someone else's id")
        assertEquals(MergeDecision.NotFound, service.confirm(userId, UUID.randomUUID()))
    }

    @Test
    fun `answers are audited with the names only`() = runBlocking<Unit> {
        pay("Hoichoi", 10); pay("Hoichoi Technologies", 20)
        val p = service.proposals(userId).single()
        service.confirm(userId, p.id)
        val entry = audits.findByEntity("merchant_merge", p.id).single()
        assertEquals("merchant.merge", entry.action)
        assertEquals("\"Hoichoi\"", entry.afterState?.get("kept").toString())
        assertTrue(entry.afterState.toString().contains("Hoichoi Technologies"))
        assertTrue(!entry.afterState.toString().contains("649"), "no amounts")
    }

    @Test
    fun `a later merge into another name moves the earlier aliases along`() = runBlocking<Unit> {
        aliases.merge(userId, "toonverse", "Toonverse", setOf("toonverse.in"), now)
        aliases.merge(userId, "toonverse tv", "Toonverse TV", setOf("toonverse"), now)
        val r = AliasResolver(aliases.aliases(userId))
        assertEquals("toonverse tv", r.key("TOONVERSE.IN"))
        assertEquals("Toonverse TV", r.name("Toonverse"))
        assertEquals(setOf("toonverse", "toonverse.in"), r.membersOf("toonverse tv"))
        aliases.merge(userId, "toonverse", "Toonverse", setOf("toonverse tv"), now)
        assertNull(AliasResolver(aliases.aliases(userId)).canonicalNameOf("toonverse tv"), "the kept name is nobody's alias")
        assertEquals("toonverse", AliasResolver(aliases.aliases(userId)).key("TOONVERSE.IN"))
    }

    @Test
    fun `only the user's own spends in the look-back count, and people need stronger evidence`() = runBlocking<Unit> {
        pay("Gaana", 10); pay("Gaana Com", 450) // the second is older than 400 days
        pay("Hungama", 10, owner = UUID.randomUUID()); pay("Hungama Digital", 10)
        pay("Amit Patel", 10, upi = "amitp@okaxis", method = PaymentMethod.UPI); pay("Amita Patel", 12, upi = "amita@okaxis", method = PaymentMethod.UPI)
        assertEquals(emptyList(), service.proposals(userId))
    }

    @Test
    fun `the analytics merchant list shows a merged merchant once`() = runBlocking<Unit> {
        pay("Hoichoi", 3, "199.00"); pay("Hoichoi Technologies", 4, "199.00")
        aliases.merge(userId, "hoichoi", "Hoichoi", setOf("hoichoi technologies"), now)
        val analytics = AnalyticsService(AliasedAnalyticsRepository(InMemoryAnalyticsRepository(transactions), aliases), clock)
        val list = analytics.merchants(userId, DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16)), ZoneId.of("Asia/Kolkata"), 10)
        assertEquals(listOf("Hoichoi" to BigDecimal("398.00")), list.map { it.merchant to it.spent })
        val plain = AnalyticsService(AliasedAnalyticsRepository(InMemoryAnalyticsRepository(transactions), InMemoryMerchantAliasRepository()), clock)
        assertEquals(2, plain.merchants(userId, DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16)), ZoneId.of("Asia/Kolkata"), 10).size)
    }

    @Test
    fun `aliases, rejected pairs and proposals validate themselves`() {
        assertFailsWith<IllegalArgumentException> { MerchantAlias(userId, "a", "a", "a") }
        assertFailsWith<IllegalArgumentException> { MerchantAlias(userId, "x", "b", "Not B") }
        assertFailsWith<IllegalArgumentException> { MerchantAlias(userId, " ", "b", "b") }
        assertFailsWith<IllegalArgumentException> { RejectedPair("b", "a") }
        assertEquals(RejectedPair("a", "b"), RejectedPair.of("b", "a"))
        val member = ProposedMember("A", "a", 1)
        assertFailsWith<IllegalArgumentException> { MergeProposal(UUID.randomUUID(), member, emptyList(), 1.0, emptySet()) }
        assertFailsWith<IllegalArgumentException> { MergeProposal(UUID.randomUUID(), member, listOf(member), 1.0, emptySet()) }
        assertTrue(AliasResolver.NONE.isEmpty)
        assertNull(AliasResolver.NONE.key(" "))
        assertNull(AliasResolver.NONE.name(null))
    }

    @Test
    fun `a huge merchant list is bounded and compared only where a match is possible`() = runBlocking<Unit> {
        val profiles = (1..3000).map { i ->
            val name = "Shop $i ${('a' + i % 26)}${('a' + (i / 26) % 26)}"
            MerchantProfile(name, name.lowercase(), MerchantNameCleaner.clean(name) ?: error("clean"), payments = 3000 - i)
        }
        val started = System.nanoTime()
        service.group(userId, profiles, emptySet())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "grouping 3,000 names stays fast")
        assertEquals(1000, MerchantGroupingService.MAX_MERCHANTS)
    }
}
