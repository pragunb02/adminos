package dev.adminos.api.domain.financial

import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.financial.mandates.MandateStatus
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** One user's scoring run: subscriptions scored, how many are flagged now, and how many failed. */
data class WasteScoreRun(val scored: Int, val flagged: Int, val failed: Int)

/**
 * Scores each active or trial subscription of a user with [WasteScorer] and stores the result
 * (AOS-509). Reads the current state every time, so a re-run gives the same scores and flags.
 */
class WasteScoreService(
    private val subscriptions: SubscriptionRepository,
    private val transactions: TransactionRepository,
    private val mandates: MandateService,
    private val clock: Clock,
    private val zone: ZoneId
) {
    private val logger = LoggerFactory.getLogger(WasteScoreService::class.java)

    suspend fun scoreUser(userId: UUID): WasteScoreRun {
        val now = clock.instant()
        val today = now.atZone(zone).toLocalDate()
        val live = subscriptions.findByUserId(userId, limit = MAX_SUBSCRIPTIONS).filter { it.status in SCORED }
        if (live.isEmpty()) return WasteScoreRun(0, 0, 0)
        val spend = transactions.spendingByCategory(userId, now.minus(30, ChronoUnit.DAYS), now).values
            .fold(BigDecimal.ZERO, BigDecimal::add)
        val dormant = mandates.list(userId, today)
            .filter { it.status == MandateStatus.ACTIVE && it.dormant }
            .map { MandateService.normalize(it.mandate.payee) }.filter { it.isNotEmpty() }
        val context = WasteContext(today, live, spend.takeIf { it.signum() > 0 }, dormant)

        var flagged = 0
        val counts = forEachIsolated(live, logger, { "waste score for subscription ${it.id}" }) { s ->
            val scored = WasteScorer.applyTo(s, WasteScorer.score(s, context), now)
            subscriptions.update(scored)
            if (scored.isFlagged) flagged++
        }
        return WasteScoreRun(counts.succeeded, flagged, counts.failed)
    }

    private companion object {
        const val MAX_SUBSCRIPTIONS = 1000
        val SCORED = setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL)
    }
}
