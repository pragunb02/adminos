package dev.adminos.api.domain.wealth.holdings

import dev.adminos.api.domain.wealth.AssetClass
import dev.adminos.api.domain.wealth.Instrument
import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.InvestmentAccount
import dev.adminos.api.domain.wealth.InvestmentAccountRepository
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.imports.HoldingStatus
import dev.adminos.api.domain.wealth.imports.ReconciliationService
import dev.adminos.api.domain.wealth.lots.HoldingLots
import dev.adminos.api.domain.wealth.lots.LotEngine
import dev.adminos.api.domain.wealth.prices.PriceService
import dev.adminos.api.domain.wealth.prices.PricedAt
import dev.adminos.api.domain.wealth.returns.CashFlow
import dev.adminos.api.domain.wealth.returns.ReturnFigure
import dev.adminos.api.domain.wealth.returns.Returns
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** One holding as the Wealth screen shows it, with everything "How we got this" needs (AOS-820). */
data class HoldingView(
    val account: InvestmentAccount,
    val instrument: Instrument,
    val lots: HoldingLots,
    /** The price used, or null when there is none ("valued at cost"). */
    val price: PricedAt?,
    val value: BigDecimal,
    val ret: ReturnFigure,
    val flows: Int
) {
    val invested: BigDecimal get() = lots.costInr
    val gain: BigDecimal get() = value - invested
    val gainRatio: BigDecimal? get() = if (invested.signum() > 0) gain.divide(invested, MathContext.DECIMAL128).setScale(6, RoundingMode.HALF_EVEN) else null
    val valuedAtCost: Boolean get() = price == null
}

data class AssetClassGroup(val assetClass: AssetClass, val holdings: List<HoldingView>) {
    val value: BigDecimal get() = holdings.fold(BigDecimal.ZERO) { a, h -> a + h.value }
    val invested: BigDecimal get() = holdings.fold(BigDecimal.ZERO) { a, h -> a + h.invested }
}

/** A holding that can't be trusted yet (reconciliation blocked it): shown in Review, kept out of the totals. */
data class BlockedHolding(val folioMasked: String, val amc: String, val status: HoldingStatus)

data class WealthOverview(
    val asOf: LocalDate,
    val groups: List<AssetClassGroup>,
    val blocked: List<BlockedHolding>,
    val realised: BigDecimal,
    val ret: ReturnFigure
) {
    val holdings: List<HoldingView> get() = groups.flatMap { it.holdings }
    val value: BigDecimal get() = groups.fold(BigDecimal.ZERO) { a, g -> a + g.value }
    val invested: BigDecimal get() = groups.fold(BigDecimal.ZERO) { a, g -> a + g.invested }
    val gain: BigDecimal get() = value - invested
    /** The newest price date among the holdings (the "prices as of" line). */
    val pricesAsOf: LocalDate? get() = holdings.mapNotNull { it.price?.observation?.date }.maxOrNull()
    val stale: Int get() = holdings.count { it.price?.stale == true }
    val valuedAtCost: Int get() = holdings.count { it.valuedAtCost }
}

/**
 * The Wealth overview (AOS-820, D-79): lots (AOS-811) valued at the latest price on or before today (AOS-810), with
 * gains and returns (AOS-812). Holdings that reconciliation blocked (AOS-805) are listed apart and never counted.
 * Everything is derived on read from the ledger, so it is always in step with imports and undo.
 */
class HoldingsService(
    private val ledger: InvestmentLedger,
    private val accounts: InvestmentAccountRepository,
    private val instruments: InstrumentRepository,
    private val prices: PriceService,
    private val reconciliation: ReconciliationService
) {
    suspend fun overview(userId: UUID, asOf: LocalDate): WealthOverview {
        val rows = ledger.active(userId).filter { !it.tradeDate.isAfter(asOf) }
        val statuses = reconciliation.current(userId)
        val blockedKeys = statuses.filter { it.blocked && it.check.instrumentId != null }.map { it.check.accountId to it.check.instrumentId }.toSet()
        val byAccount = accounts.findByUser(userId).associateBy { it.id }
        val lots = rows.groupBy { it.accountId }.values.flatMap { LotEngine.run(it).values }
            .filter { (it.accountId to it.instrumentId) !in blockedKeys }
        val instrumentById = instruments.findByIds(lots.map { it.instrumentId }).associateBy { it.id }
        val priced = prices.priceAt(instrumentById.values, asOf)

        val views = lots.filter { it.units.signum > 0 }.mapNotNull { h ->
            val account = byAccount[h.accountId] ?: return@mapNotNull null
            val instrument = instrumentById[h.instrumentId] ?: return@mapNotNull null
            val price = priced[instrument.id]
            val value = price?.observation?.price?.valueOf(h.units)?.setScale(2, RoundingMode.HALF_EVEN) ?: h.costInr.setScale(2, RoundingMode.HALF_EVEN)
            val flows = Returns.cashFlows(rows.filter { it.accountId == h.accountId && it.instrumentId == h.instrumentId })
            HoldingView(account, instrument, h, price, value, Returns.of(flows, value, asOf), flows.size)
        }
        val groups = views.groupBy { it.instrument.assetClass }
            .map { (cls, list) -> AssetClassGroup(cls, list.sortedByDescending { it.value }) }
            .sortedByDescending { it.value }
        val counted = lots.map { it.accountId to it.instrumentId }.toSet()
        val allFlows: List<CashFlow> = Returns.cashFlows(rows.filter { (it.accountId to it.instrumentId) in counted })
        val total = views.fold(BigDecimal.ZERO) { a, v -> a + v.value }
        val blocked = statuses.filter { it.blocked }.mapNotNull { s -> byAccount[s.check.accountId]?.let { BlockedHolding(it.identifier.masked, it.institution, s) } }
        return WealthOverview(asOf, groups, blocked, lots.fold(BigDecimal.ZERO) { a, l -> a + l.realisedInr }, Returns.of(allFlows, total, asOf))
    }
}
