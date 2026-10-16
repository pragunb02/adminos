package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.*
import dev.adminos.api.domain.analytics.AnalyticsFixture.IST
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/** AOS-301 on Postgres: the SQL grouping gives exactly the in-memory answers, in more than one timezone. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `postgres and in-memory agree on every view`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val pg = ExposedTransactionRepository()
        val mem = InMemoryTransactionRepository()
        AnalyticsFixture.all(user).forEach { pg.save(it); mem.save(it) }
        val sql = AnalyticsService(ExposedAnalyticsRepository())
        val ref = AnalyticsService(InMemoryAnalyticsRepository(mem))
        val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16))
        for (zone in listOf(IST, ZoneId.of("Asia/Tokyo"))) {
            assertEquals(ref.summary(user, oct, zone).normalized(), sql.summary(user, oct, zone).normalized(), "summary $zone")
            for (b in Bucket.entries) assertEquals(ref.series(user, oct, b, zone).map { it.normalized() }, sql.series(user, oct, b, zone).map { it.normalized() }, "series $b $zone")
            assertEquals(ref.categories(user, oct, zone).map { it.copy(spent = it.spent.stripTrailingZeros(), previousSpent = it.previousSpent.stripTrailingZeros()) },
                sql.categories(user, oct, zone).map { it.copy(spent = it.spent.stripTrailingZeros(), previousSpent = it.previousSpent.stripTrailingZeros()) }, "categories $zone")
            assertEquals(ref.merchants(user, oct, zone, 10).map { it.copy(spent = it.spent.stripTrailingZeros()) },
                sql.merchants(user, oct, zone, 10).map { it.copy(spent = it.spent.stripTrailingZeros()) }, "merchants $zone")
            for (c in listOf(dev.adminos.api.domain.financial.TransactionCategory.FOOD, dev.adminos.api.domain.financial.TransactionCategory.SHOPPING)) {
                assertEquals(ref.series(user, oct, Bucket.WEEK, zone, c).map { it.normalized() }, sql.series(user, oct, Bucket.WEEK, zone, c).map { it.normalized() }, "series $c $zone")
                assertEquals(ref.merchants(user, oct, zone, 10, c).map { it.copy(spent = it.spent.stripTrailingZeros()) },
                    sql.merchants(user, oct, zone, 10, c).map { it.copy(spent = it.spent.stripTrailingZeros()) }, "merchants $c $zone")
            }
        }
    }

    private fun Money.normalized() = Money(spent.stripTrailingZeros(), received.stripTrailingZeros())
    private fun Summary.normalized() = copy(current = current.normalized(), previous = previous.normalized())
    private fun SeriesPoint.normalized() = copy(money = money.normalized())
}
