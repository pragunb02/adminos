package dev.adminos.api.domain.financial

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One charge of a subscription, on the user's date. */
data class Charge(val transactionId: UUID, val amount: BigDecimal, val currency: String, val on: LocalDate) {
    init {
        require(amount.signum() > 0) { "A charge is positive" }
        require(currency.isNotBlank()) { "A charge has a currency" }
    }
}

/** The latest charge moved the price: [usual] is what it was (median of the charges before), [now] what was charged. */
data class PriceChange(val usual: BigDecimal, val now: BigDecimal, val currency: String, val on: LocalDate, val transactionId: UUID) {
    init {
        require(usual.signum() > 0 && now.signum() > 0) { "Prices are positive" }
        require(usual.compareTo(now) != 0) { "A price change changes the price" }
    }

    val isRise: Boolean get() = now > usual

    /** Signed percentage of [usual], two decimals (e.g. 23.11 or −10.00). */
    val pct: BigDecimal get() = (now - usual).multiply(BigDecimal(100)).divide(usual, 2, RoundingMode.HALF_UP)
}

/**
 * When a subscription's price changed (AOS-715, D-74; research §12). One rule for the alert, the
 * Subscriptions page's ↑/↓ and the waste score's price-rise factor (D-58):
 * - the latest charge is compared with the **median of the up-to-3 charges before it**, so one odd
 *   (discounted, prorated) charge doesn't make a "rise";
 * - in whole currency units, so GST rounding (₹648.99 → ₹649.00) isn't a change;
 * - a change is at least max(5%, ₹20) either way;
 * - a charge 8–14× the usual is a monthly plan turned yearly (and 1/14–1/8 the reverse): a plan
 *   switch, not a price change;
 * - only charges in the latest charge's currency are compared;
 * - monthly and weekly plans need 2 earlier charges; yearly and quarterly ones (few charges a year) need 1.
 */
object PriceChangeRule {
    const val REFERENCE_CHARGES = 3
    val MIN_SHARE = BigDecimal("0.05")
    val MIN_STEP = BigDecimal(20)
    /** A yearly plan costs about 10–12 months of the monthly one; with a discount or GST, 8–14. */
    val PLAN_SWITCH_RATIO: ClosedFloatingPointRange<Double> = 8.0..14.0

    fun minEarlierCharges(cycle: BillingCycle): Int = when (cycle) {
        BillingCycle.YEARLY, BillingCycle.QUARTERLY -> 1
        BillingCycle.DAILY, BillingCycle.WEEKLY, BillingCycle.MONTHLY -> 2
    }

    /** @return the change at the latest of [charges], or null when there is none (or too little to say) */
    fun evaluate(charges: List<Charge>, cycle: BillingCycle): PriceChange? {
        val sorted = charges.sortedWith(compareBy<Charge> { it.on }.thenBy { it.transactionId })
        val latest = sorted.lastOrNull() ?: return null
        val earlier = sorted.dropLast(1).filter { it.currency == latest.currency }.takeLast(REFERENCE_CHARGES)
        if (earlier.size < minEarlierCharges(cycle)) return null
        val usual = median(earlier.map { it.amount })
        val usualUnits = usual.setScale(0, RoundingMode.HALF_UP)
        val nowUnits = latest.amount.setScale(0, RoundingMode.HALF_UP)
        if (usualUnits.signum() <= 0 || nowUnits.signum() <= 0) return null // under one rupee: nothing to compare
        if (isPlanSwitch(usualUnits, nowUnits)) return null
        val step = usualUnits.multiply(MIN_SHARE).max(MIN_STEP)
        if ((nowUnits - usualUnits).abs() < step) return null
        return PriceChange(usual, latest.amount, latest.currency, latest.on, latest.transactionId)
    }

    private fun isPlanSwitch(usual: BigDecimal, now: BigDecimal): Boolean {
        val ratio = now.toDouble() / usual.toDouble()
        return ratio in PLAN_SWITCH_RATIO || (1 / ratio) in PLAN_SWITCH_RATIO
    }

    private fun median(values: List<BigDecimal>): BigDecimal {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid].setScale(2, RoundingMode.HALF_UP)
        else (s[mid - 1] + s[mid]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
    }
}

/** A recorded rise of one subscription to a new price (V34); [notifiedAt] is set once the user was told. */
data class PriceRise(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val subscriptionId: UUID,
    val oldAmount: BigDecimal,
    val newAmount: BigDecimal,
    val currency: String,
    val chargedOn: LocalDate,
    val transactionId: UUID,
    val notifiedAt: Instant? = null,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(newAmount > oldAmount) { "A rise goes up" }
        require(oldAmount.signum() > 0) { "Prices are positive" }
    }

    /** The new price in whole units: one alert per (subscription, this). */
    val priceKey: Long get() = newAmount.setScale(0, RoundingMode.HALF_UP).longValueExact()

    companion object {
        fun of(userId: UUID, subscriptionId: UUID, change: PriceChange): PriceRise {
            require(change.isRise) { "Only a rise is recorded" }
            return PriceRise(userId = userId, subscriptionId = subscriptionId, oldAmount = change.usual, newAmount = change.now,
                currency = change.currency, chargedOn = change.on, transactionId = change.transactionId)
        }
    }
}

interface PriceRiseRepository {
    /** Records [rise] unless this (subscription, price) is already recorded. @return true when it is new */
    suspend fun recordIfNew(rise: PriceRise): Boolean
    /** The user's rises not yet told, oldest first. */
    suspend fun findUnnotified(userId: UUID): List<PriceRise>
    suspend fun markNotified(id: UUID, at: Instant)
}

class InMemoryPriceRiseRepository : PriceRiseRepository {
    private val store = ConcurrentHashMap<UUID, PriceRise>()

    override suspend fun recordIfNew(rise: PriceRise): Boolean = synchronized(this) {
        if (store.values.any { it.subscriptionId == rise.subscriptionId && it.priceKey == rise.priceKey }) return false
        store[rise.id] = rise
        true
    }

    override suspend fun findUnnotified(userId: UUID): List<PriceRise> =
        store.values.filter { it.userId == userId && it.notifiedAt == null }.sortedBy { it.createdAt }

    override suspend fun markNotified(id: UUID, at: Instant) {
        store.computeIfPresent(id) { _, r -> r.copy(notifiedAt = at) }
    }
}
