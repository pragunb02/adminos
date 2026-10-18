package dev.adminos.api.domain.wealth.lots

import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.UnitPrice
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * An open lot (AOS-811): units bought on [acquiredOn] that are still held, with their remaining [cost] in the
 * instrument's currency and in rupees. [costEstimated] marks a cost the statement didn't give (a gift in, a side
 * pocket), shown as such rather than guessed silently.
 */
data class Lot(
    val sourceId: UUID,
    val acquiredOn: LocalDate,
    val units: Quantity,
    val cost: BigDecimal,
    val costInr: BigDecimal,
    val costEstimated: Boolean = false
) {
    init { require(units.signum > 0) { "an open lot holds units" } }
}

enum class DisposalKind { SALE, TRANSFER_OUT, REVERSAL, MERGER }

/** Units leaving a lot. A [DisposalKind.SALE] has proceeds and a realised gain; the others move units without one. */
data class Disposal(
    val date: LocalDate,
    val kind: DisposalKind,
    val acquiredOn: LocalDate,
    val units: Quantity,
    val cost: BigDecimal,
    val costInr: BigDecimal,
    val proceeds: BigDecimal?,
    val proceedsInr: BigDecimal?
) {
    val gain: BigDecimal? get() = proceeds?.subtract(cost)
    val gainInr: BigDecimal? get() = proceedsInr?.subtract(costInr)
    val holdingDays: Long get() = ChronoUnit.DAYS.between(acquiredOn, date)
}

/** Something the engine couldn't do exactly; the holding is shown with it instead of a silently wrong number. */
enum class LotIssue(val text: String) {
    OVERSOLD("More units left than were bought: an earlier statement is missing"),
    COST_UNKNOWN("Some units arrived without a cost (a gift or side pocket); their cost is counted as zero"),
    MERGER_UNMATCHED("A scheme merger's other side wasn't found; the new units start at their own value");
}

/** The derived state of one holding (account × instrument). */
data class HoldingLots(
    val accountId: UUID,
    val instrumentId: UUID,
    val open: List<Lot>,
    val disposals: List<Disposal>,
    /** Dividends paid out (cash to the owner), gross. */
    val dividends: BigDecimal,
    /** Taxes withheld or charged that aren't part of a cost (TDS, STT). */
    val taxes: BigDecimal,
    /** Stamp duty that had no purchase on its date to belong to. */
    val unallocatedFees: BigDecimal,
    val issues: Set<LotIssue>
) {
    val units: Quantity get() = open.fold(Quantity.ZERO) { a, l -> a + l.units }
    /** What the units still held cost ("invested"). */
    val cost: BigDecimal get() = open.fold(BigDecimal.ZERO) { a, l -> a + l.cost }
    val costInr: BigDecimal get() = open.fold(BigDecimal.ZERO) { a, l -> a + l.costInr }
    val realised: BigDecimal get() = disposals.mapNotNull { it.gain }.fold(BigDecimal.ZERO, BigDecimal::add)
    val realisedInr: BigDecimal get() = disposals.mapNotNull { it.gainInr }.fold(BigDecimal.ZERO, BigDecimal::add)

    /** Value of the units held at [price] (instrument currency), exact. */
    fun valueAt(price: UnitPrice): BigDecimal = price.valueOf(units)
    fun unrealisedAt(price: UnitPrice): BigDecimal = valueAt(price) - cost
}

/**
 * FIFO lots from the ledger (AOS-811, D-79; FIFO as CBDT Circular 768 directs for demat securities, and as MF
 * statements report). Pure: the same rows always give the same lots.
 *
 * - Purchases (buy, SIP, switch in, dividend reinvestment) open a lot at their amount; the day's stamp duty is added to
 *   the cost of that day's purchases (in order), since units are allotted on the net amount.
 * - Sales and switches out consume the oldest lots first; the realised gain is proceeds − cost, per lot, in the
 *   instrument currency and in rupees (each side at its own date's rate, as stored on the rows).
 * - A bonus opens a lot at cost 0 on its date; a split changes units, not cost or dates.
 * - A scheme merger carries cost and acquisition dates into the new scheme (it isn't a transfer for tax).
 * - A reversal (a bounced SIP) removes units from the newest lots with no gain; a transfer out (a gift) removes the
 *   oldest without one.
 * - Dividends paid out, TDS and STT are tracked, not costs.
 */
object LotEngine {
    private val MC = MathContext.DECIMAL128
    private const val COST_SCALE = 10

    /** Lots for every holding in one account's rows (any order; sorted here by date, then ledger order). */
    fun run(rows: List<InvestmentTransaction>): Map<UUID, HoldingLots> {
        require(rows.map { it.accountId }.distinct().size <= 1) { "lots are computed per account" }
        val states = LinkedHashMap<UUID, State>()
        val mergerPool = mutableMapOf<LocalDate, MutableList<Lot>>()
        val ordered = rows.withIndex().sortedWith(compareBy({ it.value.tradeDate }, { it.index })).map { it.value }
        for ((date, day) in ordered.groupBy { it.tradeDate }) {
            val (stamps, rest) = day.partition { it.type == InvestmentTxnType.STAMP_DUTY }
            // Within a day: merger outs before merger ins (so the new scheme finds the carried lots), else ledger order.
            val others = rest.sortedBy { when (it.type) { InvestmentTxnType.MERGER_OUT -> 0; InvestmentTxnType.MERGER_IN -> 2; else -> 1 } }
            val opened = mutableMapOf<UUID, MutableList<UUID>>() // instrument → the purchase rows that opened lots today
            for (r in others) {
                val s = states.getOrPut(r.instrumentId) { State(r.accountId, r.instrumentId) }
                apply(s, r, mergerPool, opened.getOrPut(r.instrumentId) { mutableListOf() })
            }
            stamps.groupBy { it.instrumentId }.forEach { (instrument, list) ->
                val s = states.getOrPut(instrument) { State(list.first().accountId, instrument) }
                val today = opened[instrument].orEmpty()
                list.forEachIndexed { k, stamp -> s.addCost(today.getOrNull(k) ?: today.lastOrNull(), stamp) }
            }
            mergerPool.remove(date) // a merger out with no merger in that day: its units left, nothing to carry
        }
        return states.mapValues { (_, s) -> s.result() }
    }

    private class State(val accountId: UUID, val instrumentId: UUID) {
        val lots = ArrayDeque<Lot>()
        val disposals = mutableListOf<Disposal>()
        var dividends: BigDecimal = BigDecimal.ZERO
        var taxes: BigDecimal = BigDecimal.ZERO
        var fees: BigDecimal = BigDecimal.ZERO
        val issues = mutableSetOf<LotIssue>()

        fun open(lot: Lot): UUID { lots.addLast(lot); return lot.sourceId }

        /** Adds a stamp-duty row's amount to the lot opened today by [source], or to unallocated fees if it's gone. */
        fun addCost(source: UUID?, stamp: InvestmentTransaction) {
            val amount = stamp.amount ?: BigDecimal.ZERO
            val index = source?.let { id -> lots.indexOfFirst { it.sourceId == id } } ?: -1
            if (index < 0) { fees += amount; return }
            val lot = lots[index]
            lots[index] = lot.copy(cost = lot.cost + amount, costInr = lot.costInr + (stamp.amountInr ?: amount))
        }

        fun result() = HoldingLots(accountId, instrumentId, lots.toList(), disposals.toList(), dividends, taxes, fees, issues.toSet())

        /** Takes [units] from the lots, oldest first (or newest first with [newestFirst]); returns the pieces taken. */
        fun take(units: Quantity, newestFirst: Boolean = false): List<Lot> {
            var left = units
            val taken = mutableListOf<Lot>()
            while (left.signum > 0 && lots.isNotEmpty()) {
                val lot = if (newestFirst) lots.removeLast() else lots.removeFirst()
                if (lot.units <= left) {
                    taken += lot
                    left -= lot.units
                } else {
                    val ratio = left.value.divide(lot.units.value, MC)
                    val cost = lot.cost.multiply(ratio, MC).setScale(COST_SCALE, RoundingMode.HALF_EVEN)
                    val costInr = lot.costInr.multiply(ratio, MC).setScale(COST_SCALE, RoundingMode.HALF_EVEN)
                    taken += lot.copy(units = left, cost = cost, costInr = costInr)
                    val rest = lot.copy(units = lot.units - left, cost = lot.cost - cost, costInr = lot.costInr - costInr)
                    if (newestFirst) lots.addLast(rest) else lots.addFirst(rest)
                    left = Quantity.ZERO
                }
            }
            if (left.signum > 0) issues += LotIssue.OVERSOLD
            return taken
        }
    }

    private fun apply(s: State, r: InvestmentTransaction, mergers: MutableMap<LocalDate, MutableList<Lot>>, openedToday: MutableList<UUID>) {
        val units = r.units
        val amount = r.amount ?: BigDecimal.ZERO
        val amountInr = r.amountInr ?: amount
        when (r.type) {
            InvestmentTxnType.BUY, InvestmentTxnType.SIP_BUY, InvestmentTxnType.SWITCH_IN, InvestmentTxnType.DIVIDEND_REINVEST ->
                openedToday += s.open(Lot(r.id, r.tradeDate, checkNotNull(units), amount, amountInr))
            InvestmentTxnType.TRANSFER_IN, InvestmentTxnType.SEGREGATION -> {
                if (r.amount == null) s.issues += LotIssue.COST_UNKNOWN
                openedToday += s.open(Lot(r.id, r.tradeDate, checkNotNull(units), amount, amountInr, costEstimated = r.amount == null))
            }
            InvestmentTxnType.BONUS -> openedToday += s.open(Lot(r.id, r.tradeDate, checkNotNull(units), BigDecimal.ZERO, BigDecimal.ZERO))
            InvestmentTxnType.SELL, InvestmentTxnType.SWITCH_OUT -> sell(s, r, checkNotNull(units).abs(), amount, amountInr)
            InvestmentTxnType.TRANSFER_OUT -> s.take(checkNotNull(units).abs()).forEach { s.disposals += disposal(r, DisposalKind.TRANSFER_OUT, it, null, null) }
            InvestmentTxnType.REVERSAL -> reverse(s, r, openedToday)
            InvestmentTxnType.SPLIT -> split(s, units)
            InvestmentTxnType.MERGER_OUT -> s.take(checkNotNull(units).abs()).forEach {
                s.disposals += disposal(r, DisposalKind.MERGER, it, null, null)
                mergers.getOrPut(r.tradeDate) { mutableListOf() } += it
            }
            InvestmentTxnType.MERGER_IN -> mergeIn(s, r, checkNotNull(units), mergers.remove(r.tradeDate).orEmpty(), openedToday)
            InvestmentTxnType.DIVIDEND_PAYOUT -> s.dividends += amount
            InvestmentTxnType.TDS, InvestmentTxnType.STT -> s.taxes += amount
            InvestmentTxnType.STAMP_DUTY, InvestmentTxnType.MISC -> Unit
        }
    }

    /** FIFO sale: proceeds are shared across the lots taken by units, each piece with its own gain. */
    private fun sell(s: State, r: InvestmentTransaction, units: Quantity, proceeds: BigDecimal, proceedsInr: BigDecimal) {
        val taken = s.take(units)
        var leftProceeds = proceeds
        var leftInr = proceedsInr
        taken.forEachIndexed { i, piece ->
            val last = i == taken.lastIndex
            val share = if (last) leftProceeds else proceeds.multiply(piece.units.value.divide(units.value, MC), MC).setScale(COST_SCALE, RoundingMode.HALF_EVEN)
            val shareInr = if (last) leftInr else proceedsInr.multiply(piece.units.value.divide(units.value, MC), MC).setScale(COST_SCALE, RoundingMode.HALF_EVEN)
            leftProceeds -= share; leftInr -= shareInr
            s.disposals += disposal(r, DisposalKind.SALE, piece, share, shareInr)
        }
    }

    /** A reversal undoes the latest purchase: units out come from the newest lots; units back in reopen a lot at the amount. */
    private fun reverse(s: State, r: InvestmentTransaction, openedToday: MutableList<UUID>) {
        val units = r.units ?: return
        if (units.signum < 0) {
            s.take(units.abs(), newestFirst = true).forEach { s.disposals += disposal(r, DisposalKind.REVERSAL, it, null, null) }
        } else if (units.signum > 0) {
            openedToday += s.open(Lot(r.id, r.tradeDate, units, r.amount ?: BigDecimal.ZERO, r.amountInr ?: r.amount ?: BigDecimal.ZERO, costEstimated = r.amount == null))
        }
    }

    /** A split or consolidation: the change in units is shared across the open lots by units; cost and dates stay. */
    private fun split(s: State, delta: Quantity?) {
        if (delta == null || delta.isZero || s.lots.isEmpty()) return
        val total = s.lots.fold(Quantity.ZERO) { a, l -> a + l.units }
        if ((total + delta).signum <= 0) { s.issues += LotIssue.OVERSOLD; s.lots.clear(); return }
        val factor = (total + delta).value.divide(total.value, MC)
        val scaled = s.lots.map { it.copy(units = Quantity.of(it.units.value.multiply(factor, MC).setScale(Quantity.MAX_SCALE, RoundingMode.HALF_EVEN))) }
        // Keep the total exact: the rounding difference goes to the newest lot.
        val drift = (total + delta) - scaled.fold(Quantity.ZERO) { a, l -> a + l.units }
        s.lots.clear()
        scaled.forEachIndexed { i, l -> s.lots.addLast(if (i == scaled.lastIndex) l.copy(units = l.units + drift) else l) }
    }

    /** The new scheme's units take over the old lots' cost and dates, apportioned by the old lots' units. */
    private fun mergeIn(s: State, r: InvestmentTransaction, units: Quantity, carried: List<Lot>, openedToday: MutableList<UUID>) {
        if (carried.isEmpty()) {
            s.issues += LotIssue.MERGER_UNMATCHED
            openedToday += s.open(Lot(r.id, r.tradeDate, units, r.amount ?: BigDecimal.ZERO, r.amountInr ?: r.amount ?: BigDecimal.ZERO, costEstimated = true))
            return
        }
        val oldTotal = carried.fold(Quantity.ZERO) { a, l -> a + l.units }
        var left = units
        carried.forEachIndexed { i, old ->
            val newUnits = if (i == carried.lastIndex) left
                else Quantity.of(units.value.multiply(old.units.value.divide(oldTotal.value, MC), MC).setScale(Quantity.MAX_SCALE, RoundingMode.HALF_EVEN))
            left -= newUnits
            if (newUnits.signum > 0) s.open(Lot(r.id, old.acquiredOn, newUnits, old.cost, old.costInr, old.costEstimated))
        }
    }

    private fun disposal(r: InvestmentTransaction, kind: DisposalKind, piece: Lot, proceeds: BigDecimal?, proceedsInr: BigDecimal?) =
        Disposal(r.tradeDate, kind, piece.acquiredOn, piece.units, piece.cost, piece.costInr, proceeds, proceedsInr)
}

/**
 * The INR gain on a foreign holding split into what the price did and what the rupee did (WEALTH-RESEARCH §3.6):
 * price effect = (P₁ − P₀) × Q × FX₀, FX effect = P₁ × Q × (FX₁ − FX₀). They add up to P₁·Q·FX₁ − P₀·Q·FX₀ exactly.
 */
data class GainSplit(val priceEffect: BigDecimal, val fxEffect: BigDecimal) {
    val total: BigDecimal get() = priceEffect + fxEffect

    companion object {
        fun of(p0: BigDecimal, p1: BigDecimal, quantity: Quantity, fx0: BigDecimal, fx1: BigDecimal) = GainSplit(
            priceEffect = (p1 - p0).multiply(quantity.value).multiply(fx0),
            fxEffect = p1.multiply(quantity.value).multiply(fx1 - fx0)
        )
    }
}
