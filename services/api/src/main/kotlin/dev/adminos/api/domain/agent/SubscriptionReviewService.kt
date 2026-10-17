package dev.adminos.api.domain.agent

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.financial.mandates.MandateRail
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.financial.mandates.MandateStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class SubscriptionNotFoundException : RuntimeException("Subscription not found")

/** How to stop a subscription: AdminOS can't cancel for you, so it says exactly where (AOS-513). */
data class CancellationGuidance(val subscription: Subscription, val via: MandateRail?, val steps: List<String>)

/**
 * The user's decisions on subscriptions (AOS-513): keep a flagged one, and how to cancel one.
 * Replaces route-level repository calls (layer rule) and the legacy "cancellation queued" stub,
 * which claimed work that never happened.
 */
class SubscriptionReviewService(
    private val subscriptions: SubscriptionRepository,
    private val mandates: MandateService,
    private val audit: AuditService,
    private val clock: Clock = Clock.systemUTC()
) {
    /** Keeps a flagged subscription; the factors behind today's waste score count as seen (AOS-509). */
    suspend fun keep(userId: UUID, id: UUID): Subscription {
        val sub = owned(userId, id)
        val updated = subscriptions.update(sub.keep(clock.instant()))
        audit.log(
            userId = userId, actor = ActorType.USER, action = AuditActions.SUBSCRIPTION_KEEP, entityType = "subscription", entityId = id,
            beforeState = JsonObject(mapOf("is_flagged" to JsonPrimitive(sub.isFlagged))),
            afterState = JsonObject(mapOf("is_flagged" to JsonPrimitive(false), "flag_dismissed_at" to JsonPrimitive(updated.flagDismissedAt.toString())))
        )
        return updated
    }

    /** Where to cancel: through the AutoPay mandate when one pays it, otherwise with the service itself. */
    suspend fun cancellationGuidance(userId: UUID, id: UUID): CancellationGuidance {
        val sub = owned(userId, id)
        val names = listOfNotNull(sub.name, sub.merchantName).map(MandateService::normalize).filter { it.isNotEmpty() }
        val mandate = mandates.list(userId, LocalDate.now(clock)).firstOrNull { v ->
            v.status == MandateStatus.ACTIVE && MandateService.normalize(v.mandate.payee).let { p -> names.any { n -> p == n || p in n || n in p } }
        }
        val name = sub.name.ifBlank { sub.merchantName ?: "the service" }
        val alsoService = "Cancel inside $name too (account → subscription or membership), so it doesn't move to another payment method."
        val steps = when (mandate?.mandate?.rail) {
            MandateRail.UPI_AUTOPAY -> listOf("Open the UPI app you set it up in → AutoPay / Mandates.", "Find ${mandate.mandate.payee} and revoke the mandate.", alsoService)
            MandateRail.NACH -> listOf("In your bank's net banking or app, open e-mandates / NACH.", "Cancel the mandate for ${mandate.mandate.payee} (or ask the bank to).", alsoService)
            MandateRail.CARD_SI -> listOf("In your card's net banking or app, remove the standing instruction for ${mandate.mandate.payee}.", alsoService)
            MandateRail.UNKNOWN, null -> listOf(
                "Cancel in $name's account or app settings (subscription or membership).",
                "If it's billed through the App Store or Google Play, cancel it there instead.",
                "AdminOS marks it ended once no charge arrives for two cycles."
            )
        }
        audit.log(userId = userId, actor = ActorType.USER, action = AuditActions.SUBSCRIPTION_CANCEL, entityType = "subscription", entityId = id)
        return CancellationGuidance(sub, mandate?.mandate?.rail, steps)
    }

    private suspend fun owned(userId: UUID, id: UUID): Subscription =
        subscriptions.findById(id)?.takeIf { it.userId == userId } ?: throw SubscriptionNotFoundException()
}
