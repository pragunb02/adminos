package dev.adminos.api.domain.financial

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ── Transaction Repository ──

interface TransactionRepository {
    suspend fun save(transaction: Transaction): Transaction
    suspend fun findById(id: UUID): Transaction?
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
        limit: Int = 20
    ): List<Transaction>

    suspend fun countByUserId(
        userId: UUID,
        from: Instant? = null,
        to: Instant? = null,
        category: TransactionCategory? = null,
        type: TransactionType? = null,
        accountId: UUID? = null,
        isRecurring: Boolean? = null,
        merchantSearch: String? = null
    ): Long

    suspend fun update(transaction: Transaction): Transaction

    suspend fun findDebitsByUserIdSince(userId: UUID, since: Instant): List<Transaction>

    suspend fun spendingByCategory(
        userId: UUID,
        from: Instant,
        to: Instant
    ): Map<TransactionCategory, java.math.BigDecimal>
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
        cursor: String? = null,
        limit: Int = 20
    ): List<Subscription>
    suspend fun countByUserId(userId: UUID, status: SubscriptionStatus? = null): Long
    suspend fun findByUserAndMerchantAndCycle(
        userId: UUID,
        merchantName: String,
        billingCycle: BillingCycle
    ): Subscription?
    suspend fun update(subscription: Subscription): Subscription
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
    suspend fun findOverdue(): List<Bill>
    suspend fun update(bill: Bill): Bill
}
