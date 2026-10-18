package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.AnalyticsRepository
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.coach.CoachFixture
import dev.adminos.api.domain.coach.MovableClock
import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.lenses.InMemoryLensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AOS-914 on Postgres: the leaks read `own_share` through the transaction repository and count the share, settlements
 * (V41's nature) never reach them, and the leaks total equals the analytics' spend for the same transactions, in
 * memory and on Postgres alike.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoachSplitShareIntegrationTest {
    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val clock = MovableClock(LocalDateTime.of(2026, 11, 18, 12, 0).atZone(CoachFixture.IST).toInstant())
    private val week = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 18))

    private fun contract(txns: TransactionRepository, analyticsRepo: AnalyticsRepository) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val bill = txns.save(CoachFixture.txn("Toit", "4000.00", LocalDateTime.of(2026, 11, 17, 22, 30), user = user))
        assertTrue(txns.setOwnShare(bill.id, BigDecimal("1000.00")))
        txns.save(CoachFixture.txn("Toit", "3000.00", LocalDateTime.of(2026, 11, 17, 23, 0), nature = TransactionNature.SETTLEMENT, user = user)
            .copy(type = TransactionType.CREDIT))
        txns.save(CoachFixture.txn("Toit", "450.00", LocalDateTime.of(2026, 11, 16, 20, 0), nature = TransactionNature.SETTLEMENT, user = user))
        txns.save(CoachFixture.txn("Zepto", "500.00", LocalDateTime.of(2026, 11, 16, 19, 0), user = user))

        val leaks = LeakService(txns, LensService(LensMatcher(), InMemoryLensOverrideRepository(), clock), clock)
        val report = leaks.report(user, week, CoachFixture.IST)
        assertEquals(0, report.lenses.single { it.lens.id == LensId("nights_out") }.numbers.spent.compareTo(BigDecimal("1000")))
        assertEquals(0, report.total.spent.compareTo(BigDecimal("1500")))
        assertTrue(leaks.facts(user, week, CoachFixture.IST).none { it.nature == TransactionNature.SETTLEMENT })

        val spent = AnalyticsService(analyticsRepo, clock).summary(user, week, CoachFixture.IST).current.spent
        assertEquals(0, spent.compareTo(report.total.spent), "every spend here is a leak, so the leaks total equals the console's spend")
    }

    @Test fun `leaks count the share on Postgres`() = contract(ExposedTransactionRepository(), ExposedAnalyticsRepository())

    @Test fun `leaks count the share in memory, the same`() = InMemoryTransactionRepository().let { contract(it, InMemoryAnalyticsRepository(it)) }
}
