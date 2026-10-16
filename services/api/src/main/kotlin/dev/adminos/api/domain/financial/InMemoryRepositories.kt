package dev.adminos.api.domain.financial

import dev.adminos.api.domain.common.CursorCodec
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ── In-Memory Transaction Repository ──

class InMemoryTransactionRepository : TransactionRepository {
    private val store = ConcurrentHashMap<UUID, Transaction>()

    override suspend fun save(transaction: Transaction): Transaction {
        store[transaction.id] = transaction
        return transaction
    }

    override suspend fun findById(id: UUID): Transaction? = store[id]

    override suspend fun findByIngestEventId(ingestEventId: UUID): Transaction? =
        store.values.firstOrNull { it.ingestEventId == ingestEventId }

    override suspend fun findMatchCandidates(userId: UUID, type: TransactionType, amount: java.math.BigDecimal, from: Instant, to: Instant): List<Transaction> =
        store.values.filter { it.userId == userId && it.type == type && it.amount.compareTo(amount) == 0 && it.transactedAt in from..to }

    override suspend fun delete(id: UUID) { store.remove(id) }

    override suspend fun findInWindow(userId: UUID, type: TransactionType, from: Instant, to: Instant,
                                      minAmount: java.math.BigDecimal?, maxAmount: java.math.BigDecimal?): List<Transaction> =
        store.values.filter { it.userId == userId && it.type == type && it.transactedAt in from..to &&
            (minAmount == null || it.amount >= minAmount) && (maxAmount == null || it.amount <= maxAmount) }

    override suspend fun findByPayee(userId: UUID, match: RuleMatch, value: String): List<Transaction> =
        store.values.filter { t ->
            t.userId == userId && when (match) {
                RuleMatch.VPA -> t.upiVpa?.trim()?.lowercase() == value
                RuleMatch.MERCHANT -> t.merchantName?.trim()?.lowercase() == value
            }
        }

    override suspend fun findByUserId(
        userId: UUID, from: Instant?, to: Instant?,
        category: TransactionCategory?, type: TransactionType?,
        accountId: UUID?, isRecurring: Boolean?,
        merchantSearch: String?, cursor: String?, limit: Int, nature: TransactionNature?
    ): List<Transaction> {
        var results = store.values.filter { it.userId == userId }
        nature?.let { n -> results = results.filter { it.nature == n } }
        from?.let { f -> results = results.filter { it.transactedAt >= f } }
        to?.let { t -> results = results.filter { it.transactedAt <= t } }
        category?.let { c -> results = results.filter { it.category == c } }
        type?.let { t -> results = results.filter { it.type == t } }
        accountId?.let { a -> results = results.filter { it.accountId == a } }
        isRecurring?.let { r -> results = results.filter { it.isRecurring == r } }
        merchantSearch?.let { q ->
            val lower = q.lowercase()
            results = results.filter { it.merchantName?.lowercase()?.contains(lower) == true }
        }
        results = results.sortedWith(compareByDescending<Transaction> { it.transactedAt }.thenByDescending { it.id })
        cursor?.let { c ->
            val (at, id) = parseTransactionCursor(CursorCodec.key(c))
            results = results.filter { it.transactedAt < at || (id != null && it.transactedAt == at && it.id < id) }
        }
        return results.take(limit)
    }

    override suspend fun countByUserId(
        userId: UUID, from: Instant?, to: Instant?,
        category: TransactionCategory?, type: TransactionType?,
        accountId: UUID?, isRecurring: Boolean?, merchantSearch: String?, nature: TransactionNature?
    ): Long {
        if (nature != null) return findByUserId(userId, from, to, category, type, accountId, isRecurring, merchantSearch, null, Int.MAX_VALUE, nature).size.toLong()
        var results = store.values.filter { it.userId == userId }
        from?.let { f -> results = results.filter { it.transactedAt >= f } }
        to?.let { t -> results = results.filter { it.transactedAt <= t } }
        category?.let { c -> results = results.filter { it.category == c } }
        type?.let { t -> results = results.filter { it.type == t } }
        accountId?.let { a -> results = results.filter { it.accountId == a } }
        isRecurring?.let { r -> results = results.filter { it.isRecurring == r } }
        merchantSearch?.let { q ->
            val lower = q.lowercase()
            results = results.filter { it.merchantName?.lowercase()?.contains(lower) == true }
        }
        return results.size.toLong()
    }

    override suspend fun update(transaction: Transaction): Transaction {
        store[transaction.id] = transaction.copy(updatedAt = Instant.now())
        return store[transaction.id]!!
    }

    override suspend fun findDebitsByUserIdSince(userId: UUID, since: Instant): List<Transaction> =
        store.values.filter {
            it.userId == userId && it.nature == TransactionNature.SPEND && it.transactedAt >= since
        }.sortedBy { it.transactedAt }

    override suspend fun spendingByCategory(
        userId: UUID, from: Instant, to: Instant
    ): Map<TransactionCategory, BigDecimal> {
        fun sumBy(nature: TransactionNature) = store.values
            .filter { it.userId == userId && it.nature == nature && it.transactedAt in from..to }
            .groupBy { it.category }
            .mapValues { (_, txns) -> txns.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount } }
        return netOfRefunds(sumBy(TransactionNature.SPEND), sumBy(TransactionNature.REFUND))
    }

    override suspend fun getCategoryAverage(
        userId: UUID, category: String, days: Int
    ): BigDecimal {
        val since = Instant.now().minus(days.toLong(), java.time.temporal.ChronoUnit.DAYS)
        val cat = try { TransactionCategory.valueOf(category.uppercase()) } catch (_: Exception) { null }
        val txns = store.values.filter {
            it.userId == userId &&
                it.nature == TransactionNature.SPEND &&
                it.transactedAt >= since &&
                (cat == null || it.category == cat)
        }
        if (txns.isEmpty()) return BigDecimal.ZERO
        val total = txns.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
        return total.divide(BigDecimal(txns.size), 2, java.math.RoundingMode.HALF_UP)
    }

    override suspend fun getRecentByMerchant(
        userId: UUID, merchantName: String, hours: Int
    ): List<Transaction> {
        val since = Instant.now().minus(hours.toLong(), java.time.temporal.ChronoUnit.HOURS)
        return store.values.filter {
            it.userId == userId &&
                it.merchantName?.equals(merchantName, ignoreCase = true) == true &&
                it.transactedAt >= since
        }.sortedByDescending { it.transactedAt }
    }
}

// ── In-Memory Account Repository ──

class InMemoryAccountRepository : AccountRepository {
    private val store = ConcurrentHashMap<UUID, Account>()

    override suspend fun save(account: Account): Account {
        store[account.id] = account
        return account
    }

    override suspend fun findById(id: UUID): Account? = store[id]

    override suspend fun findByUserId(userId: UUID): List<Account> =
        store.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }

    override suspend fun findByUserAndBankAndLast4(userId: UUID, bankCode: String, accountLast4: String): Account? =
        store.values.find { it.userId == userId && it.bankCode == bankCode && it.accountLast4 == accountLast4 }

    override suspend fun update(account: Account): Account {
        store[account.id] = account.copy(updatedAt = Instant.now())
        return store[account.id]!!
    }
}

// ── In-Memory Subscription Repository ──

class InMemorySubscriptionRepository : SubscriptionRepository {
    private val store = ConcurrentHashMap<UUID, Subscription>()

    override suspend fun save(subscription: Subscription): Subscription {
        store[subscription.id] = subscription
        return subscription
    }

    override suspend fun findById(id: UUID): Subscription? = store[id]

    override suspend fun findByUserId(
        userId: UUID, status: SubscriptionStatus?, flagged: Boolean?, cursor: String?, limit: Int
    ): List<Subscription> {
        var results = store.values.filter { it.userId == userId }
        status?.let { s -> results = results.filter { it.status == s } }
        flagged?.let { f -> results = results.filter { it.isFlagged == f } }
        results = results.sortedByDescending { it.createdAt }
        cursor?.let { c ->
            val cursorInstant = Instant.parse(CursorCodec.key(c))
            results = results.filter { it.createdAt < cursorInstant }
        }
        return results.take(limit)
    }

    override suspend fun countByUserId(userId: UUID, status: SubscriptionStatus?, flagged: Boolean?): Long {
        var results = store.values.filter { it.userId == userId }
        status?.let { s -> results = results.filter { it.status == s } }
        flagged?.let { f -> results = results.filter { it.isFlagged == f } }
        return results.size.toLong()
    }

    override suspend fun findByUserAndMerchantAndCycle(
        userId: UUID, merchantName: String, billingCycle: BillingCycle
    ): Subscription? =
        store.values.find {
            it.userId == userId &&
                it.merchantName?.lowercase() == merchantName.lowercase() &&
                it.billingCycle == billingCycle
        }

    override suspend fun update(subscription: Subscription): Subscription {
        store[subscription.id] = subscription.copy(updatedAt = Instant.now())
        return store[subscription.id]!!
    }
}

// ── In-Memory Bill Repository ──

class InMemoryBillRepository(private val clock: java.time.Clock = java.time.Clock.systemDefaultZone()) : BillRepository {
    private val store = ConcurrentHashMap<UUID, Bill>()

    override suspend fun save(bill: Bill): Bill {
        store[bill.id] = bill
        return bill
    }

    override suspend fun findById(id: UUID): Bill? = store[id]

    override suspend fun findByUserId(
        userId: UUID, status: BillStatus?, from: LocalDate?, to: LocalDate?,
        cursor: String?, limit: Int
    ): List<Bill> {
        var results = store.values.filter { it.userId == userId }
        status?.let { s -> results = results.filter { it.status == s } }
        from?.let { f -> results = results.filter { it.dueDate >= f } }
        to?.let { t -> results = results.filter { it.dueDate <= t } }
        results = results.sortedWith(compareBy<Bill>({ it.dueDate }, { it.id }))
        cursor?.let { c ->
            val (cursorDate, cursorId) = parseBillCursor(CursorCodec.key(c))
            results = results.filter { it.dueDate > cursorDate || (cursorId != null && it.dueDate == cursorDate && it.id > cursorId) }
        }
        return results.take(limit)
    }

    override suspend fun countByUserId(userId: UUID, status: BillStatus?): Long {
        var results = store.values.filter { it.userId == userId }
        status?.let { s -> results = results.filter { it.status == s } }
        return results.size.toLong()
    }

    override suspend fun findPendingBills(userId: UUID): List<Bill> =
        store.values.filter {
            it.userId == userId && it.status in listOf(BillStatus.UPCOMING, BillStatus.DUE, BillStatus.OVERDUE)
        }.sortedBy { it.dueDate }

    override suspend fun findUpcoming(userId: UUID, withinDays: Int): List<Bill> {
        val today = LocalDate.now(clock)
        val cutoff = today.plusDays(withinDays.toLong())
        return store.values.filter {
            it.userId == userId &&
                it.status in listOf(BillStatus.UPCOMING, BillStatus.DUE) &&
                it.dueDate in today..cutoff
        }.sortedBy { it.dueDate }
    }

    // Find upcoming bills across ALL users (for cron)
    override suspend fun findUpcoming(withinDays: Int): List<Bill> {
        val today = LocalDate.now(clock)
        val cutoff = today.plusDays(withinDays.toLong())
        return store.values.filter {
            it.status in listOf(BillStatus.UPCOMING, BillStatus.DUE) &&
                it.dueDate in today..cutoff
        }
    }

    override suspend fun findOverdue(): List<Bill> {
        val today = LocalDate.now(clock)
        return store.values.filter {
            it.status in listOf(BillStatus.UPCOMING, BillStatus.DUE) && it.dueDate < today
        }
    }

    override suspend fun update(bill: Bill): Bill {
        store[bill.id] = bill.copy(updatedAt = Instant.now())
        return store[bill.id]!!
    }
}
