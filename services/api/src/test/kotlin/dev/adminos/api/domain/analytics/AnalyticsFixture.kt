package dev.adminos.api.domain.analytics

import dev.adminos.api.domain.financial.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A small month of money for one user, shared by the in-memory and Postgres analytics tests. */
object AnalyticsFixture {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    fun txn(user: UUID, day: LocalDate, h: Int, m: Int, amount: String, nature: TransactionNature, category: TransactionCategory,
            merchant: String?, zone: ZoneId = IST) = Transaction(
        userId = user, nature = nature, category = category, merchantName = merchant, amount = BigDecimal(amount),
        type = if (nature == TransactionNature.SPEND || nature == TransactionNature.TRANSFER) TransactionType.DEBIT else TransactionType.CREDIT,
        transactedAt = day.atTime(h, m).atZone(zone).toInstant()
    )

    /**
     * September: ₹1,000 food. October 1–16 (Thu Oct 1 … Fri Oct 16, 2026):
     * - food: Swiggy 300 (Oct 1), Swiggy 200 (Oct 5), Chai 50 (Oct 5, 23:30 IST: still Oct 5 in India)
     * - shopping: Amazon 999 (Oct 2), Amazon refund 300 (Oct 9)
     * - income: salary 100,000 (Oct 1)
     * - ignored: transfer 5,000 (Oct 3), reversal pair 249 (Oct 4)
     */
    fun all(user: UUID): List<Transaction> {
        fun oct(d: Int) = LocalDate.of(2026, 10, d)
        return listOf(
            txn(user, LocalDate.of(2026, 9, 20), 12, 0, "1000.00", TransactionNature.SPEND, TransactionCategory.FOOD, "Swiggy"),
            txn(user, oct(1), 9, 0, "300.00", TransactionNature.SPEND, TransactionCategory.FOOD, "Swiggy"),
            txn(user, oct(1), 10, 0, "100000.00", TransactionNature.INCOME, TransactionCategory.OTHER, "Acme"),
            txn(user, oct(2), 18, 0, "999.00", TransactionNature.SPEND, TransactionCategory.SHOPPING, "Amazon"),
            txn(user, oct(3), 8, 0, "5000.00", TransactionNature.TRANSFER, TransactionCategory.TRANSFER, null),
            txn(user, oct(4), 8, 0, "249.00", TransactionNature.REVERSAL, TransactionCategory.FOOD, "Swiggy"),
            txn(user, oct(5), 13, 0, "200.00", TransactionNature.SPEND, TransactionCategory.FOOD, "Swiggy"),
            txn(user, oct(5), 23, 30, "50.00", TransactionNature.SPEND, TransactionCategory.FOOD, null),
            txn(user, oct(9), 11, 0, "300.00", TransactionNature.REFUND, TransactionCategory.SHOPPING, "Amazon"),
        )
    }
}
