package dev.adminos.api.domain.coach.leaks

import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import java.time.LocalDateTime
import java.util.UUID

/**
 * One synthetic week of leaks, shared by the in-memory and Postgres tests (AOS-902). India time.
 * Range Mon 12 – Sun 18 Oct 2026; previous period Mon 5 – Sun 11 Oct.
 * - quick commerce: Zepto 400 (Mon 13:00), Zepto 600 (Tue 23:30, late), Blinkit 300 (Wed 01:30, late),
 *   Zepto refund 100 (Thu); before: Zepto 500 (Thu 8 Oct)
 * - food delivery: Swiggy 500 (Fri 21:00), Swiggy 250 from a statement (Sat, no time)
 * - shopping: only an Amazon refund of 999 (Wed) for an earlier purchase
 * - never counted: a friend paid by UPI (Sat 23:00, entertainment), a transfer, a salary
 */
object LeakFixture {
    private fun oct(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m)

    fun week(user: UUID): List<Transaction> = listOf(
        txn("Zepto", "400.00", oct(12, 13), category = TransactionCategory.SHOPPING, subcategory = "groceries", vpa = "zepto@ybl", user = user),
        txn("Zepto", "600.00", oct(13, 23, 30), category = TransactionCategory.SHOPPING, subcategory = "groceries", user = user),
        txn("Blinkit", "300.00", oct(14, 1, 30), category = TransactionCategory.SHOPPING, subcategory = "groceries", user = user),
        txn("Zepto", "100.00", oct(15, 10), nature = TransactionNature.REFUND, category = TransactionCategory.SHOPPING, user = user),
        txn("Zepto", "500.00", oct(8, 12), category = TransactionCategory.SHOPPING, subcategory = "groceries", user = user),
        txn("Swiggy", "500.00", oct(16, 21), category = TransactionCategory.FOOD, subcategory = "food_delivery", user = user),
        txn("Swiggy", "250.00", oct(17, 12), category = TransactionCategory.FOOD, subcategory = "food_delivery", user = user, source = "statement"),
        txn("Amazon", "999.00", oct(14, 18), nature = TransactionNature.REFUND, category = TransactionCategory.SHOPPING, user = user),
        txn("Rohit Sharma", "1500.00", oct(17, 23), category = TransactionCategory.ENTERTAINMENT, subcategory = "nightlife", vpa = "rohit.sharma92@oksbi", user = user),
        txn(null, "5000.00", oct(13, 9), nature = TransactionNature.TRANSFER, category = TransactionCategory.TRANSFER, user = user),
        txn("Acme Payroll", "90000.00", oct(12, 9), nature = TransactionNature.INCOME, user = user)
    )
}
