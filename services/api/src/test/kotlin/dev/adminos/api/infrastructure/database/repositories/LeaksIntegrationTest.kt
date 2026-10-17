package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.CoachFixture
import dev.adminos.api.domain.coach.leaks.LeakFixture
import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.leaks.LeakServiceTest
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** AOS-902 on Postgres: the same week gives the same leaks and drill-downs as the in-memory run. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaksIntegrationTest {
    private val txns = ExposedTransactionRepository()
    private val service = LeakService(txns, LensService(LensMatcher(), ExposedLensOverrideRepository()))

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `a week of leaks and its drill-downs match the in-memory numbers`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        LeakFixture.week(user).forEach { txns.save(it) }
        val week = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        LeakServiceTest.assertWeek(service.report(user, week, CoachFixture.IST))
        LeakServiceTest.assertDrillDown(service, user)
    }

    @Test
    fun `a transaction at the next local midnight is not in the range`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        // 18:30 UTC on 18 Oct is exactly 00:00 on 19 Oct in India.
        txns.save(CoachFixture.txn("Zepto", "70.00", LocalDateTime.of(2026, 10, 18, 18, 30), zone = ZoneOffset.UTC, user = user))
        txns.save(CoachFixture.txn("Zepto", "30.00", LocalDateTime.of(2026, 10, 18, 18, 29, 59), zone = ZoneOffset.UTC, user = user))
        val week = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        val qc = service.report(user, week, CoachFixture.IST).lenses.first { it.lens.id.value == "quick_commerce" }
        assertEquals(0, qc.numbers.spent.compareTo(BigDecimal("30")))
    }
}
