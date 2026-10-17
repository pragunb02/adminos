package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillStatus
import dev.adminos.api.domain.financial.BillType
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.forecast.InMemoryForecastHistoryRepository
import dev.adminos.api.domain.forecast.SpendRow
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AOS-714 on Postgres: the forecast's history matches the in-memory twin, in more than one zone. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForecastHistoryIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun txn(user: UUID, type: TransactionType, amount: String, merchant: String?, at: String, recurring: Boolean = false) =
        Transaction(userId = user, type = type, amount = BigDecimal(amount), merchantName = merchant, isRecurring = recurring, transactedAt = Instant.parse(at))

    @Test
    fun `postgres and in-memory agree on spending, income and the first day`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val pg = ExposedTransactionRepository()
        val pgBills = ExposedBillRepository()
        val mem = InMemoryTransactionRepository()
        val memBills = InMemoryBillRepository()
        val rent = txn(user, TransactionType.DEBIT, "18000.00", "Landlord", "2026-10-05T06:00:00Z")
        val rows = listOf(
            txn(user, TransactionType.TRANSFER, "5000.00", null, "2026-06-01T06:00:00Z"),
            txn(user, TransactionType.DEBIT, "250.00", "Chai Point", "2026-10-01T06:00:00Z"),
            txn(user, TransactionType.DEBIT, "120.50", "Chai Point", "2026-10-01T10:00:00Z"),
            txn(user, TransactionType.REFUND, "100.00", "Chai Point", "2026-10-01T11:00:00Z"),
            txn(user, TransactionType.DEBIT, "5000.00", "Zerodha", "2026-10-03T06:00:00Z", recurring = true),
            // 19:00 UTC on 9 Oct is 10 Oct in India and in Tokyo.
            txn(user, TransactionType.DEBIT, "999.00", null, "2026-10-09T19:00:00Z"),
            txn(user, TransactionType.CREDIT, "85000.00", "ACME", "2026-10-01T03:30:00Z"),
            txn(user, TransactionType.REVERSAL, "70.00", "Chai Point", "2026-10-02T06:00:00Z"),
            // A hostile merchant name from an email or SMS is data, never SQL.
            txn(user, TransactionType.DEBIT, "1.00", "x'); DROP TABLE transactions; --", "2026-10-04T06:00:00Z"),
            rent
        )
        rows.forEach { pg.save(it); mem.save(it) }
        val bill = Bill(userId = user, billerName = "Rent", billType = BillType.RENT, amount = BigDecimal("18000.00"), dueDate = LocalDate.of(2026, 10, 5),
            status = BillStatus.PAID, paymentTxnId = rent.id)
        pgBills.save(bill); memBills.save(bill)

        val sql = ExposedForecastHistoryRepository()
        val ref = InMemoryForecastHistoryRepository(mem, memBills)
        val october = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        for (zone in listOf(ZoneId.of("Asia/Kolkata"), ZoneId.of("Asia/Tokyo"))) {
            assertEquals(normalized(ref.spending(user, october, zone)), normalized(sql.spending(user, october, zone)), "spending $zone")
            assertEquals(ref.incomes(user, october, zone).map { it.copy(amount = it.amount.stripTrailingZeros()) },
                sql.incomes(user, october, zone).map { it.copy(amount = it.amount.stripTrailingZeros()) }, "incomes $zone")
            assertEquals(ref.firstActivityDay(user, zone), sql.firstActivityDay(user, zone), "first day $zone")
        }
        val ist = normalized(sql.spending(user, october, ZoneId.of("Asia/Kolkata")))
        assertEquals(SpendRow(LocalDate.of(2026, 10, 1), "Chai Point", false, BigDecimal("270.5")), ist.first())
        assertEquals(true, ist.single { it.merchant == "Landlord" }.recurring)
        assertEquals(LocalDate.of(2026, 10, 10), ist.single { it.merchant == null }.day)
        assertNull(sql.firstActivityDay(UUID.randomUUID(), ZoneId.of("Asia/Kolkata")))
        assertEquals(BigDecimal("1"), ist.single { it.merchant == "x'); DROP TABLE transactions; --" }.amount)
        assertEquals(rows.size, pg.findByUserId(user, limit = 100).size, "the table is intact")
    }

    private fun normalized(rows: List<SpendRow>) =
        rows.map { it.copy(amount = it.amount.stripTrailingZeros()) }.sortedWith(compareBy<SpendRow> { it.day }.thenBy { it.merchant ?: "" }.thenBy { it.recurring })
}
