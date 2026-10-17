package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.AliasedAnalyticsRepository
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.SubscriptionDetectorService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.grouping.AliasResolver
import dev.adminos.api.domain.financial.grouping.MergeDecision
import dev.adminos.api.domain.financial.grouping.MerchantGroupingService
import dev.adminos.api.domain.financial.grouping.RejectedPair
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** AOS-709 on Postgres: V46 aliases and rejections, and a merge seen by the detector and the analytics. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantAliasIntegrationTest {
    private val aliases = ExposedMerchantAliasRepository()
    private val transactions = ExposedTransactionRepository()
    private val subscriptions = ExposedSubscriptionRepository()
    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `merges round-trip, never chain, and keep their first date`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        aliases.merge(user, "toonverse", "Toonverse", setOf("toonverse.in", "toonverse"), now)
        aliases.merge(user, "toonverse tv", "Toonverse TV", setOf("toonverse"), now.plusSeconds(60))
        val stored = aliases.aliases(user).associateBy { it.aliasKey }
        assertEquals(setOf("toonverse", "toonverse.in"), stored.keys)
        assertEquals(setOf("toonverse tv"), stored.values.map { it.canonicalKey }.toSet())
        assertEquals(now, stored.getValue("toonverse.in").createdAt)
        aliases.merge(user, "toonverse", "Toonverse", setOf("toonverse tv"), now)
        assertEquals("toonverse", AliasResolver(aliases.aliases(user)).key("Toonverse TV"))
        assertEquals(emptyList(), aliases.aliases(PostgresTestDatabase.newUser()), "per user")
    }

    @Test
    fun `rejections are stored once`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val pair = RejectedPair.of("kuku fm", "kukufm")
        aliases.reject(user, setOf(pair), now)
        aliases.reject(user, setOf(pair), now)
        assertEquals(setOf(pair), aliases.rejections(user))
    }

    @Test
    fun `a confirmed merge is one subscription and one merchant on Postgres`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        listOf(95L to "Spotifyindia", 65L to "Spotifyindia", 35L to "Spotify Ab", 5L to "Spotify Ab").forEach { (days, name) ->
            transactions.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("119.00"), merchantName = name,
                paymentMethod = PaymentMethod.CARD_CREDIT, transactedAt = now.minus(days, ChronoUnit.DAYS)))
        }
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val calendar = UserCalendar(ExposedUserRepository(), clock)
        val grouping = MerchantGroupingService(transactions, aliases, calendar, AuditService(ExposedAuditRepository(), CoroutineScope(Dispatchers.Unconfined)), clock = clock)
        assertIs<MergeDecision.Merged>(grouping.confirm(user, grouping.proposals(user).single().id))

        val detector = SubscriptionDetectorService(transactions, subscriptions, calendar, ExposedPriceRiseRepository(), aliases)
        val sub = detector.detectSubscriptions(user).single()
        assertEquals(BillingCycle.MONTHLY, sub.billingCycle)
        assertEquals(4, sub.transactionIds.size)

        val analytics = AnalyticsService(AliasedAnalyticsRepository(ExposedAnalyticsRepository(), aliases), clock)
        val today = now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
        val merchants = analytics.merchants(user, DateRange(today.minusDays(100), today), ZoneId.of("Asia/Kolkata"), 10)
        assertEquals(listOf("Spotify Ab" to BigDecimal("476.00")), merchants.map { it.merchant to it.spent })
    }
}
