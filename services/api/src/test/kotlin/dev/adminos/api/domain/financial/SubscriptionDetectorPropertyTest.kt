package dev.adminos.api.domain.financial

import kotlinx.coroutines.runBlocking
import net.jqwik.api.*
import net.jqwik.api.constraints.IntRange
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Property 4: A merchant with N identical monthly charges over 90 days
 * is always detected as a monthly subscription.
 *
 * **Validates: Requirements 7.1, 7.2**
 */
class SubscriptionDetectorPropertyTest {

    @Property(tries = 50)
    fun `merchant with N identical monthly charges over 90 days is detected as monthly subscription`(
        @ForAll @IntRange(min = 3, max = 5) chargeCount: Int,
        @ForAll("merchantNames") merchantName: String,
        @ForAll("amounts") amount: Double
    ) = runBlocking {
        // Arrange
        val transactionRepo = InMemoryTransactionRepository()
        val subscriptionRepo = InMemorySubscriptionRepository()
        val detector = SubscriptionDetectorService(transactionRepo, subscriptionRepo)

        val userId = UUID.randomUUID()
        val now = Instant.now()
        val amountBd = BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP)

        // Create N monthly charges going back from now
        // Each charge is ~30 days apart (within the 25-35 day range)
        for (i in 0 until chargeCount) {
            val txnDate = now.minus((chargeCount - 1 - i).toLong() * 30, ChronoUnit.DAYS)
            val txn = Transaction(
                userId = userId,
                type = TransactionType.DEBIT,
                amount = amountBd,
                merchantName = merchantName,
                transactedAt = txnDate
            )
            transactionRepo.save(txn)
        }

        // Act
        val detected = detector.detectSubscriptions(userId)

        // Assert — must detect exactly one monthly subscription for this merchant
        val matching = detected.filter {
            it.merchantName?.lowercase() == merchantName.lowercase() &&
                it.billingCycle == BillingCycle.MONTHLY
        }

        assert(matching.size == 1) {
            "Expected 1 monthly subscription for '$merchantName' with $chargeCount charges, " +
                "but found ${matching.size}. Detected: ${detected.map { "${it.merchantName}:${it.billingCycle}" }}"
        }

        val sub = matching.first()
        assert(sub.amount.compareTo(amountBd) == 0) {
            "Subscription amount should be $amountBd but was ${sub.amount}"
        }
        assert(sub.status == SubscriptionStatus.ACTIVE) {
            "Subscription should be ACTIVE but was ${sub.status}"
        }
    }

    @Provide
    fun merchantNames(): Arbitrary<String> =
        Arbitraries.of("Netflix", "Spotify", "Hotstar", "YouTube Premium", "Amazon Prime", "JioCinema")

    @Provide
    fun amounts(): Arbitrary<Double> =
        Arbitraries.doubles().between(49.0, 1999.0).ofScale(2)
}
