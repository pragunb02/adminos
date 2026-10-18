package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.common.formatInr
import dev.adminos.api.domain.financial.PriceRise
import dev.adminos.api.domain.financial.PriceRiseRepository
import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** What one user's price-rise run did (counts only). */
data class PriceRiseRun(val sent: Int, val failed: Int)

/**
 * Tells the user about each recorded price rise once (AOS-715, D-74): "Netflix went up: ₹649 → ₹799
 * (+23%)". The detector records a rise once per (subscription, new price); this sends the ones not
 * yet told and marks them, so a failed send is simply tried again on the next run. Delivery follows
 * the user's subscription-alert switch and quiet hours (D-51).
 */
class PriceRiseAlerts(
    private val rises: PriceRiseRepository,
    private val subscriptions: SubscriptionRepository,
    private val notifications: NotificationService,
    private val calendar: UserCalendar,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(PriceRiseAlerts::class.java)

    suspend fun notifyPending(userId: UUID): PriceRiseRun {
        val pending = rises.findUnnotified(userId)
        if (pending.isEmpty()) return PriceRiseRun(0, 0)
        val zone = calendar.zoneOf(userId).id
        var sent = 0
        val counts = forEachIsolated(pending, logger, { "price-rise alert ${it.id}" }) { rise ->
            val subscription = subscriptions.findById(rise.subscriptionId)
            if (subscription == null || subscription.userId != userId) {
                logger.warn("Price rise {} has no subscription for its user: marked done without an alert", rise.id)
            } else {
                val stored = notifications.send(
                    userId = userId, type = NotificationType.SUBSCRIPTION_FLAG,
                    title = title(subscription, rise), body = body(rise),
                    data = mapOf("entity_type" to "subscription", "entity_id" to subscription.id.toString(), "action" to "view_subscription"),
                    userTimezone = zone
                )
                if (stored.isNotEmpty()) sent++ // empty: the user switched subscription alerts off
            }
            rises.markNotified(rise.id, clock.instant())
        }
        return PriceRiseRun(sent, counts.failed)
    }

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        /** Longest subscription name put in a notice: a merchant chooses its own name, and a notice isn't a billboard. */
        const val MAX_NAME = 60

        fun title(s: Subscription, r: PriceRise): String {
            val name = displayName(s.name).ifBlank { displayName(s.merchantName.orEmpty()) }.ifBlank { "A subscription" }
            return "$name went up: ${money(r.oldAmount, r.currency)} → ${money(r.newAmount, r.currency)} (+${percent(r)}%)"
        }

        /**
         * The name as plain, single-line text (AOS-715 security review): control and invisible formatting
         * characters (newlines, RTL overrides) are removed and it is cut to [MAX_NAME], so a hostile merchant
         * name can't fake a second line or reverse the text of a push notice. Markup stays literal text.
         */
        fun displayName(raw: String): String {
            val plain = raw.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
                .map { if (Character.getType(it) == Character.CONTROL.toInt()) ' ' else it }.joinToString("")
                .replace(Regex("\\s+"), " ").trim()
            return if (plain.length <= MAX_NAME) plain else plain.take(MAX_NAME - 1).trimEnd() + "…"
        }

        fun body(r: PriceRise): String =
            "Charged ${money(r.newAmount, r.currency)} on ${DAY.format(r.chargedOn)}; it was usually ${money(r.oldAmount, r.currency)}. " +
                "If it isn't worth the new price, Subscriptions shows how to cancel."

        /** Whole percent of the old price, rounded half up. */
        fun percent(r: PriceRise): Int =
            (r.newAmount - r.oldAmount).multiply(BigDecimal(100)).divide(r.oldAmount, 0, RoundingMode.HALF_UP).intValueExact()

        private fun money(amount: BigDecimal, currency: String): String =
            if (currency == "INR") amount.formatInr() else "$currency ${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
    }
}
