package dev.adminos.api.domain.wealth.lots

import dev.adminos.api.domain.wealth.InvestmentLedger
import java.util.UUID

/** Lots for every holding of a user, derived from the active ledger (AOS-811); nothing is stored, so nothing drifts. */
class LotService(private val ledger: InvestmentLedger) {
    suspend fun holdings(userId: UUID): List<HoldingLots> =
        ledger.active(userId).groupBy { it.accountId }.values.flatMap { LotEngine.run(it).values }
}
