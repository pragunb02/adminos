package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.BillType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * One past or current bill as the projector sees it (AOS-721): who billed, for what, when it was due and how much.
 * [biller] is the name the bill came with; [accountRef] tells two accounts at the same biller apart (two phones).
 * Status doesn't matter here: a paid, open or cancelled bill all mean "this month's bill has arrived".
 */
data class PastBill(
    val type: BillType,
    val biller: String,
    val accountRef: String?,
    val dueDate: LocalDate,
    val amount: BigDecimal
) {
    override fun toString() = "PastBill($type, $dueDate)" // never the amount or the biller
}

/**
 * A monthly bill that hasn't arrived yet for [month], expected from the user's own history (AOS-721, D-171):
 * [amount] is the median of the last [MonthlyBillProjector.MIN_MONTHS] bills, [expectedOn] the usual due day.
 */
data class ProjectedBill(
    val type: BillType,
    val name: String,
    val month: YearMonth,
    val expectedOn: LocalDate,
    val amount: BigDecimal,
    /** How many months running it was billed before [month]. */
    val monthsSeen: Int,
    /** The first and last month of that run, for "How we got this". */
    val seenFrom: YearMonth,
    val seenTo: YearMonth
) {
    init {
        require(name.isNotBlank()) { "A projected bill has a name" }
        require(amount.signum() > 0) { "A projected bill is a positive amount" }
        require(monthsSeen >= 1) { "A projected bill was seen at least once" }
    }

    override fun toString() = "ProjectedBill($type, $month, $expectedOn)" // never the amount or the biller
}

/** The projected bills a window expects, each listed ("How we got this"). */
data class ProjectedBillsEstimate(val items: List<ProjectedBill>) : ComponentEstimate {
    override val amount: BigDecimal get() = items.fold(BigDecimal.ZERO) { acc, b -> acc + b.amount }
}

/**
 * Learns each regular monthly biller from past bills and projects the bills that haven't arrived yet (AOS-721),
 * deterministically, so each projection can be explained (D-67). Pure: the caller loads the bills.
 *
 * 1. Only utility bills are projected ([PROJECTED_TYPES]); other bills, and bills of zero or less, are not learnt from.
 * 2. Bills are grouped by biller: the type, the biller name (case, punctuation and spacing ignored) and the account
 *    reference (two phones at one operator are two billers). A month is "billed" when the biller has any bill due in
 *    it; when it has several (a bill and its correction), the largest is that month's bill.
 * 3. A biller is regular for a target month (this month, then next month) when it was billed in each of the
 *    [MIN_MONTHS] calendar months right before it. Only real bills count, so next month is projected only when this
 *    month's bill has arrived too. Quarterly and annual billers, and a biller that skipped one of those months, are
 *    never projected. A bill that drifted into the neighbouring month leaves a hole in the run, so the biller isn't
 *    projected then either (the safe side).
 * 4. The amount is the median of those [MIN_MONTHS] months' bills; the day is the median of their due days, moved to
 *    the month's last day when the month is shorter (the 31st is the 30th in a 30-day month).
 * 5. The expected day must be after [today]. When this month's usual day is today or has passed without a bill, it
 *    isn't projected: the bill may have been paid outside AdminOS, and a late bill isn't guessed.
 * 6. Never counted twice: the target month has arrived, and isn't projected, when any bill of the same type (any
 *    name, account or amount, any status: paid early, open or cancelled) is due in it or within [ARRIVED_WITHIN_DAYS]
 *    days of the expected day. This is looser than the biller key on purpose, because a biller's name, account
 *    reference and due day can change from month to month as bills are read; with two homes' electricity, one
 *    arrived bill hides the other's projection, an accepted under-count.
 *
 * Known limit: a bill paid outside AdminOS before its usual day, with no bill row, is still projected once (and its
 * payment is in "spent so far" too).
 */
class MonthlyBillProjector {
    /**
     * The bills expected after [today] in this month and the next that haven't arrived, from [bills] (any status,
     * at least the last [HISTORY_MONTHS] months through next month), sorted by expected day, then name.
     */
    fun project(bills: List<PastBill>, today: LocalDate): List<ProjectedBill> {
        val thisMonth = YearMonth.from(today)
        val targets = listOf(thisMonth, thisMonth.plusMonths(1))
        val utility = bills.filter { it.type in PROJECTED_TYPES }
        val arrivedByType = utility.groupBy({ it.type }, { it.dueDate })
        return utility.asSequence()
            .filter { it.amount.signum() > 0 }
            .groupBy { BillerKey(it.type, billerKey(it.biller), billerKey(it.accountRef)) }
            .values
            .flatMap { group ->
                val byMonth = monthlyBills(group)
                val sameType = arrivedByType[group.first().type].orEmpty()
                targets.mapNotNull { target -> projectMonth(byMonth, target, today)?.takeUnless { hasArrived(it, sameType) } }
            }
            .sortedWith(compareBy<ProjectedBill> { it.expectedOn }.thenBy { it.name.lowercase() }.thenBy { it.name })
    }

    /** One bill per billed month: the largest of that month's bills (then the latest due), so a correction doesn't add up. */
    private fun monthlyBills(group: List<PastBill>): Map<YearMonth, PastBill> =
        group.groupBy { YearMonth.from(it.dueDate) }
            .mapValues { (_, inMonth) -> inMonth.maxWith(compareBy<PastBill> { it.amount }.thenBy { it.dueDate }) }

    /** The projection for [target] from this biller's own history, before the "has it arrived" check. */
    private fun projectMonth(byMonth: Map<YearMonth, PastBill>, target: YearMonth, today: LocalDate): ProjectedBill? {
        val run = generateSequence(target.minusMonths(1)) { it.minusMonths(1) }.takeWhile { it in byMonth }.toList()
        if (run.size < MIN_MONTHS) return null
        val recent = run.take(MIN_MONTHS).map { byMonth.getValue(it) }
        val day = Stats.median(recent.map { BigDecimal(it.dueDate.dayOfMonth) }).toInt() // odd count: one of the real days
        val expectedOn = target.atDay(minOf(day, target.lengthOfMonth()))
        if (!expectedOn.isAfter(today)) return null // the usual day has come without a bill: not guessed
        val latest = recent.first()
        return ProjectedBill(
            type = latest.type,
            name = latest.biller.trim().ifBlank { label(latest.type) },
            month = target,
            expectedOn = expectedOn,
            amount = Stats.median(recent.map { it.amount }).setScale(2, RoundingMode.HALF_UP),
            monthsSeen = run.size,
            seenFrom = run.last(),
            seenTo = run.first()
        )
    }

    /** A bill of the same type is due in the projected month or near its expected day (rule 6). */
    private fun hasArrived(projected: ProjectedBill, sameTypeDueDates: List<LocalDate>): Boolean =
        sameTypeDueDates.any { due ->
            YearMonth.from(due) == projected.month || kotlin.math.abs(ChronoUnit.DAYS.between(due, projected.expectedOn)) <= ARRIVED_WITHIN_DAYS
        }

    private data class BillerKey(val type: BillType, val biller: String, val account: String)

    companion object {
        /** A biller is regular once billed this many months running. */
        const val MIN_MONTHS = 3
        /** How far back the caller loads bills. */
        const val HISTORY_MONTHS = 6L
        /** A same-type bill due this close to the expected day is that month's bill, even across a month boundary. */
        const val ARRIVED_WITHIN_DAYS = 10L

        /**
         * The monthly utility bills that are projected (the ticket's electricity, phone and broadband, plus water and
         * gas). Not projected: credit-card and pay-later bills (paid by a transfer whose purchases are already counted,
         * D-39); rent and EMIs (usually a mandate or standing transfer, already a known due); insurance (annual or
         * quarterly); other (too vague to call regular).
         */
        val PROJECTED_TYPES: Set<BillType> = setOf(BillType.ELECTRICITY, BillType.MOBILE, BillType.INTERNET, BillType.WATER, BillType.GAS)

        /** Case, punctuation and spacing ignored, letters of any script kept ("Sample Power Co." = "SAMPLE POWER CO"). */
        private fun billerKey(s: String?): String = s.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** A readable name for a bill that came without one. */
        private fun label(type: BillType): String = type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Projected monthly bills counted on their expected days, inside the window only. */
class ProjectedBillsComponent(projections: List<ProjectedBill>) : ForecastComponent {
    private val projections = projections.sortedWith(compareBy<ProjectedBill> { it.expectedOn }.thenBy { it.name.lowercase() })

    override fun estimate(window: DateRange?): ProjectedBillsEstimate =
        ProjectedBillsEstimate(if (window == null) emptyList() else projections.filter { !it.expectedOn.isBefore(window.from) && !it.expectedOn.isAfter(window.to) })
}
