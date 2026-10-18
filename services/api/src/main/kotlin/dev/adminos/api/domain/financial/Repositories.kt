package dev.adminos.api.domain.financial

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ── Transaction Repository ──

interface TransactionRepository {
    suspend fun save(transaction: Transaction): Transaction
    suspend fun findById(id: UUID): Transaction?
    suspend fun findByIngestEventId(ingestEventId: UUID): Transaction?
    /** Same user, type and exact amount, transacted in [from, to]: possible same-payment matches (AOS-207). */
    suspend fun findMatchCandidates(userId: UUID, type: TransactionType, amount: java.math.BigDecimal, from: Instant, to: Instant): List<Transaction>
    suspend fun delete(id: UUID)
    /** Transactions of [type] in [from, to] with amount in [minAmount, maxAmount] (bounds optional): refund/reversal candidates. */
    suspend fun findInWindow(userId: UUID, type: TransactionType, from: Instant, to: Instant,
                             minAmount: java.math.BigDecimal? = null, maxAmount: java.math.BigDecimal? = null): List<Transaction>
    /** Every transaction of [userId] paid to this payee (case-insensitive UPI ID or merchant name), for remembered rules (AOS-210). */
    suspend fun findByPayee(userId: UUID, match: RuleMatch, value: String): List<Transaction>
    suspend fun findByUserId(
        userId: UUID,
        from: Instant? = null,
        to: Instant? = null,
        category: TransactionCategory? = null,
        type: TransactionType? = null,
        accountId: UUID? = null,
        isRecurring: Boolean? = null,
        merchantSearch: String? = null,
        cursor: String? = null,
        limit: Int = 20,
        /** spend / income / transfer / refund / reversal (D-39, AOS-307). */
        nature: TransactionNature? = null
    ): List<Transaction>

    suspend fun countByUserId(
        userId: UUID,
        from: Instant? = null,
        to: Instant? = null,
        category: TransactionCategory? = null,
        type: TransactionType? = null,
        accountId: UUID? = null,
        isRecurring: Boolean? = null,
        merchantSearch: String? = null,
        nature: TransactionNature? = null
    ): Long

    /** Writes every field except [Transaction.ownShare], which only [setOwnShare] changes. */
    suspend fun update(transaction: Transaction): Transaction

    /**
     * Sets (or with null clears) the owner's share of a split bill (AOS-911). One narrow write, so a concurrent
     * [update] holding an older copy can't undo it, and it can't undo an update.
     * @return false when the transaction doesn't exist
     */
    suspend fun setOwnShare(id: UUID, share: java.math.BigDecimal?): Boolean

    /** Spending (nature = SPEND) since [since]: excludes transfers, refunds and reversals (D-39). */
    suspend fun findDebitsByUserIdSince(userId: UUID, since: Instant): List<Transaction>

    /** Net spending per category: SPEND minus REFUNDs in the period (D-39); transfers and reversals don't count. */
    suspend fun spendingByCategory(
        userId: UUID,
        from: Instant,
        to: Instant
    ): Map<TransactionCategory, java.math.BigDecimal>

    suspend fun getCategoryAverage(
        userId: UUID,
        category: String,
        days: Int
    ): java.math.BigDecimal

    suspend fun getRecentByMerchant(
        userId: UUID,
        merchantName: String,
        hours: Int
    ): List<Transaction>
}

// ── Account Repository ──

interface AccountRepository {
    suspend fun save(account: Account): Account
    suspend fun findById(id: UUID): Account?
    suspend fun findByUserId(userId: UUID): List<Account>
    suspend fun findByUserAndBankAndLast4(userId: UUID, bankCode: String, accountLast4: String): Account?
    suspend fun update(account: Account): Account
}

// ── Subscription Repository ──

interface SubscriptionRepository {
    suspend fun save(subscription: Subscription): Subscription
    suspend fun findById(id: UUID): Subscription?
    suspend fun findByUserId(
        userId: UUID,
        status: SubscriptionStatus? = null,
        flagged: Boolean? = null,
        cursor: String? = null,
        limit: Int = 20
    ): List<Subscription>
    suspend fun countByUserId(userId: UUID, status: SubscriptionStatus? = null, flagged: Boolean? = null): Long
    suspend fun findByUserAndMerchantAndCycle(
        userId: UUID,
        merchantName: String,
        billingCycle: BillingCycle
    ): Subscription?
    suspend fun update(subscription: Subscription): Subscription
    /** Active and trial subscriptions of every user whose next billing date is in [from]..[to] (renewal reminders, AOS-503). */
    suspend fun findRenewingBetween(from: LocalDate, to: LocalDate): List<Subscription>
}

// ── Bill Repository ──

interface BillRepository {
    suspend fun save(bill: Bill): Bill
    suspend fun findById(id: UUID): Bill?
    suspend fun findByUserId(
        userId: UUID,
        status: BillStatus? = null,
        from: LocalDate? = null,
        to: LocalDate? = null,
        cursor: String? = null,
        limit: Int = 20
    ): List<Bill>
    suspend fun countByUserId(userId: UUID, status: BillStatus? = null): Long
    suspend fun findPendingBills(userId: UUID): List<Bill>
    suspend fun findUpcoming(userId: UUID, withinDays: Int = 30): List<Bill>
    suspend fun findUpcoming(withinDays: Int): List<Bill> // across all users — for cron
    suspend fun findOverdue(): List<Bill>
    suspend fun update(bill: Bill): Bill
}

/** Spending per category minus refunds credited to that category (D-39); categories that net to ≤ 0 are dropped. */
fun netOfRefunds(spend: Map<TransactionCategory, java.math.BigDecimal>, refunds: Map<TransactionCategory, java.math.BigDecimal>): Map<TransactionCategory, java.math.BigDecimal> =
    spend.mapValues { (c, v) -> v - (refunds[c] ?: java.math.BigDecimal.ZERO) }.filterValues { it.signum() > 0 }

/**
 * The feed pages by (time, id), newest first (AOS-307): many statement rows share a timestamp, so time
 * alone would skip the rest of that instant on the next page. A time-only cursor from before still works.
 */
fun transactionCursorKey(t: Transaction) = "${t.transactedAt}|${t.id}"

fun parseTransactionCursor(key: String): Pair<java.time.Instant, UUID?> {
    val id = key.substringAfter('|', "").takeIf { it.isNotEmpty() }
    return java.time.Instant.parse(key.substringBefore('|')) to id?.let(UUID::fromString)
}
