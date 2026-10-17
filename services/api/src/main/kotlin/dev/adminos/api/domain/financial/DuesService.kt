package dev.adminos.api.domain.financial

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class DueKind { BILL, RENEWAL, MANDATE }

/** One thing to pay on a day: a bill (from email) or an expected subscription renewal (AOS-503). */
data class DueItem(
    val kind: DueKind,
    /** The bill's or the subscription's id. */
    val id: UUID,
    val name: String,
    val amount: BigDecimal,
    val minimumDue: BigDecimal? = null,
    val date: LocalDate,
    /** A bill's status (upcoming, due, overdue, paid); renewals are "expected". */
    val status: String,
    /** Bill type (credit_card, electricity…), billing cycle (monthly…), or mandate rail (upi_autopay…). */
    val detail: String,
    val trial: Boolean = false
)

data class DueTotals(
    /** Unpaid bills this month, plus earlier overdue ones (current month only). */
    val open: BigDecimal,
    val paid: BigDecimal,
    val renewals: BigDecimal
)

data class DuesMonth(val month: YearMonth, val today: LocalDate, val items: List<DueItem>, val overdue: List<DueItem>, val totals: DueTotals)

/**
 * The dues calendar (AOS-503): a month of bills by due date and subscription renewals projected
 * from their next billing date. Renewals are only projected from today on (past ones are already
 * charges in the feed) and for at most a year ahead; daily subscriptions show only their next date.
 * Overdue bills from earlier months appear with the current month, so nothing slips away.
 */
class DuesService(
    private val bills: BillRepository,
    private val subscriptions: SubscriptionRepository,
    private val mandates: dev.adminos.api.domain.financial.mandates.MandateService
) {
    suspend fun month(userId: UUID, month: YearMonth, today: LocalDate): DuesMonth {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()

        val billItems = bills.findByUserId(userId, from = first, to = last, limit = MAX_ITEMS)
            .filter { it.status != BillStatus.CANCELLED }
            .map { it.toItem() }
        // Mandate debits (AOS-502) are the precise version of a renewal: when a subscription is paid by one, show only the mandate.
        val mandateViews = mandates.list(userId, today).filter { it.status == dev.adminos.api.domain.financial.mandates.MandateStatus.ACTIVE }
        val mandateItems = mandateViews.flatMap { v -> mandateDates(v, first, last, today).mapNotNull { d -> v.toItem(d) } }
        val paidByMandate = mandateViews.map { dev.adminos.api.domain.financial.mandates.MandateService.normalize(it.mandate.payee) }.filter { it.isNotEmpty() }
        val renewalItems = subscriptions.findByUserId(userId, limit = MAX_ITEMS)
            .filter { it.status == SubscriptionStatus.ACTIVE || it.status == SubscriptionStatus.TRIAL }
            .filterNot { s -> coveredByMandate(s, paidByMandate) }
            .flatMap { s -> renewalDates(s, first, last, today).map { d -> s.toItem(d) } } + mandateItems
        val overdue = if (month == YearMonth.from(today)) {
            bills.findByUserId(userId, status = BillStatus.OVERDUE, limit = MAX_ITEMS).filter { it.dueDate.isBefore(first) }.map { it.toItem() }
        } else emptyList()

        val items = (billItems + renewalItems).sortedWith(compareBy<DueItem> { it.date }.thenBy { it.kind }.thenBy { it.name.lowercase() })
        val open = (billItems.filter { it.status != BillStatus.PAID.name.lowercase() } + overdue).sumOf { it.amount }
        val paid = billItems.filter { it.status == BillStatus.PAID.name.lowercase() }.sumOf { it.amount }
        return DuesMonth(month, today, items, overdue.sortedBy { it.date }, DueTotals(open, paid, renewalItems.sumOf { it.amount }))
    }

    private fun Bill.toItem() = DueItem(
        kind = DueKind.BILL, id = id, name = billerName,
        amount = if (status == BillStatus.PAID) paidAmount ?: amount else amount,
        minimumDue = minimumDue, date = dueDate, status = status.name.lowercase(), detail = billType.name.lowercase()
    )

    private fun Subscription.toItem(date: LocalDate) = DueItem(
        kind = DueKind.RENEWAL, id = id, name = name.ifBlank { merchantName ?: "Subscription" }, amount = amount,
        date = date, status = "expected", detail = billingCycle.name.lowercase(), trial = status == SubscriptionStatus.TRIAL
    )

    private fun dev.adminos.api.domain.financial.mandates.MandateView.toItem(date: LocalDate): DueItem? {
        val amount = (if (date == mandate.nextDebitOn) mandate.nextDebitAmount else null) ?: mandate.amount ?: mandate.lastDebitAmount ?: return null
        return DueItem(kind = DueKind.MANDATE, id = mandate.id, name = mandate.payee, amount = amount, date = date,
            status = "expected", detail = mandate.rail.name.lowercase())
    }

    companion object {
        private const val MAX_ITEMS = 500

        private fun coveredByMandate(s: Subscription, payees: List<String>): Boolean {
            val names = listOfNotNull(s.name, s.merchantName).map { dev.adminos.api.domain.financial.mandates.MandateService.normalize(it) }.filter { it.isNotEmpty() }
            return names.any { n -> payees.any { p -> p == n || p in n || n in p } }
        }

        /** A mandate's debits within the month, from today on, at most a year ahead; unscheduled ones only on the announced date. */
        internal fun mandateDates(v: dev.adminos.api.domain.financial.mandates.MandateView, first: LocalDate, last: LocalDate, today: LocalDate): List<LocalDate> {
            val next = v.nextDebitOn ?: return emptyList()
            val horizon = minOf(last, today.plusYears(1))
            val dates = generateSequence(next) { v.mandate.frequency.after(it) }.takeWhile { !it.isAfter(horizon) }.take(400).toList()
            return dates.filter { !it.isBefore(first) && !it.isAfter(last) && !it.isBefore(today) }
        }

        /** Renewal dates of [s] within [first]..[last], from [today] on, at most a year ahead. */
        internal fun renewalDates(s: Subscription, first: LocalDate, last: LocalDate, today: LocalDate): List<LocalDate> {
            val next = s.nextBillingDate ?: return emptyList()
            val horizon = minOf(last, today.plusYears(1))
            val dates = if (s.billingCycle == BillingCycle.DAILY) listOf(next)
            else generateSequence(0L) { it + 1 }.map { i -> step(next, s.billingCycle, i) }.takeWhile { !it.isAfter(horizon) }.take(400).toList()
            return dates.filter { !it.isBefore(first) && !it.isAfter(last) && !it.isBefore(today) }
        }

        /** The i-th renewal after [next]; month-based cycles count from [next] so the 31st doesn't drift to the 28th. */
        private fun step(next: LocalDate, cycle: BillingCycle, i: Long): LocalDate = when (cycle) {
            BillingCycle.DAILY -> next.plusDays(i)
            BillingCycle.WEEKLY -> next.plusWeeks(i)
            BillingCycle.MONTHLY -> next.plusMonths(i)
            BillingCycle.QUARTERLY -> next.plusMonths(3 * i)
            BillingCycle.YEARLY -> next.plusYears(i)
        }
    }
}
