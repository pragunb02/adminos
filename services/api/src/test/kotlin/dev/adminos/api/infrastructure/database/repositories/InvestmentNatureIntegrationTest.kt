package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.wealth.flows.InvestmentFlowService
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
import kotlin.test.assertEquals

/** AOS-815 on Postgres: the `investment` nature exists (V73), round-trips, filters, and is outside spent and received. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvestmentNatureIntegrationTest {
    private val txns = ExposedTransactionRepository()
    private val zone = ZoneId.of("Asia/Kolkata")

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `an investment debit is stored, found by nature, and counts as neither spent nor received`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("10000.00"), nature = TransactionNature.INVESTMENT,
            merchantRaw = "ACH D- EXAMPLE MUTUAL FUND SIP", transactedAt = Instant.parse("2026-02-10T04:00:00Z")))
        txns.save(Transaction(userId = user, type = TransactionType.CREDIT, amount = BigDecimal("4000.00"), nature = TransactionNature.INVESTMENT,
            merchantRaw = "NEFT CR-EXAMPLE MUTUAL FUND-REDEMPTION", transactedAt = Instant.parse("2026-02-12T04:00:00Z")))
        txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("250.00"), merchantName = "Swiggy",
            transactedAt = Instant.parse("2026-02-11T04:00:00Z")))
        assertEquals(2, txns.findByUserId(user, nature = TransactionNature.INVESTMENT, limit = 10).size)
        val summary = AnalyticsService(ExposedAnalyticsRepository()).summary(user, DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), zone)
        assertEquals(0, BigDecimal("250").compareTo(summary.current.spent))
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.current.received))
        val report = InvestmentFlowService(txns, ExposedInvestmentLedger()).report(user, Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"), zone)
        assertEquals(0, BigDecimal("6000").compareTo(report.net))
    }
}
