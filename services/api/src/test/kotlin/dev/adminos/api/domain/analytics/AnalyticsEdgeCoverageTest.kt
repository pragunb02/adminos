package dev.adminos.api.domain.analytics

import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.InfraIngestTestKit.assertRoundTrips
import dev.adminos.api.InfraIngestTestKit.routeTest
import dev.adminos.api.InfraIngestTestKit.tokenFor
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.domain.analytics.AnalyticsFixture.IST
import dev.adminos.api.domain.analytics.AnalyticsFixture.txn
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Analytics edge cases: refunds-only spend, unknown merchants, empty periods and the DTOs (AOS-312). */
class AnalyticsEdgeCoverageTest {

    private val owner = user()
    private val txns = InMemoryTransactionRepository()
    private val clock = Clock.fixed(LocalDate.of(2026, 10, 16).atTime(12, 0).atZone(IST).toInstant(), IST)
    private val service = AnalyticsService(InMemoryAnalyticsRepository(txns), clock)
    private val october = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private fun oct(d: Int) = LocalDate.of(2026, 10, d)
    private fun save(day: Int, amount: String, nature: TransactionNature, category: TransactionCategory, merchant: String?) =
        runBlocking { txns.save(txn(owner.id, oct(day), 12, 0, amount, nature, category, merchant)) }

    @Test
    fun `a category with only refunds has no share and does not inflate the total`() = runBlocking<Unit> {
        save(2, "500.00", TransactionNature.SPEND, TransactionCategory.FOOD, "Swiggy")
        save(3, "200.00", TransactionNature.REFUND, TransactionCategory.SHOPPING, "Amazon")
        val byCategory = service.categories(owner.id, october, IST).associateBy { it.category }
        assertEquals(1.0, byCategory[TransactionCategory.FOOD]!!.share)
        assertEquals(0.0, byCategory[TransactionCategory.SHOPPING]!!.share)
        assertEquals(BigDecimal("-200.00"), byCategory[TransactionCategory.SHOPPING]!!.spent)
    }

    @Test
    fun `nothing spent means every share is zero`() = runBlocking<Unit> {
        save(3, "200.00", TransactionNature.REFUND, TransactionCategory.SHOPPING, "Amazon")
        assertEquals(listOf(0.0), service.categories(owner.id, october, IST).map { it.share })
    }

    @Test
    fun `blank and missing merchants are grouped as Unknown`() = runBlocking<Unit> {
        save(2, "50.00", TransactionNature.SPEND, TransactionCategory.FOOD, null)
        save(2, "70.00", TransactionNature.SPEND, TransactionCategory.FOOD, "  ")
        val m = service.merchants(owner.id, october, IST, 10).single()
        assertEquals(AnalyticsService.UNKNOWN_MERCHANT, m.merchant)
        assertEquals(BigDecimal("120.00"), m.spent)
    }

    @Test
    fun `a merchant with only refunds is left out of top merchants`() = runBlocking<Unit> {
        save(3, "200.00", TransactionNature.REFUND, TransactionCategory.SHOPPING, "Amazon")
        assertTrue(service.merchants(owner.id, october, IST, 10).isEmpty())
    }

    @Test
    fun `a merchant's category is the one it was spent on most`() = runBlocking<Unit> {
        save(2, "100.00", TransactionNature.SPEND, TransactionCategory.FOOD, "BigBasket")
        save(3, "900.00", TransactionNature.SPEND, TransactionCategory.SHOPPING, "BigBasket")
        assertEquals(TransactionCategory.SHOPPING, service.merchants(owner.id, october, IST, 10).single().category)
    }

    @Test
    fun `too many buckets is rejected`() {
        val e = kotlin.test.assertFailsWith<IllegalArgumentException> { runBlocking { service.series(owner.id, DateRange(oct(1).minusDays(500), oct(1)), Bucket.DAY, IST) } }
        assertTrue(e.message!!.startsWith("bucket:"))
    }

    // ── Routes ──

    private fun analyticsTest(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = routeTest({ analyticsRoutes(service) }, block)
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.get(path: String) = client.get(path) { bearerAuth(tokenFor(owner)) }

    @Test
    fun `summary with nothing in the previous period has no change percentage`() = analyticsTest {
        save(2, "100.00", TransactionNature.SPEND, TransactionCategory.FOOD, "Swiggy")
        val data = Json.parseToJsonElement(get("/api/v1/analytics/summary?from=2026-10-01&to=2026-10-16").bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(JsonNull, data["spentChangePct"])
    }

    @Test
    fun `series accepts week and month buckets`() = analyticsTest {
        for (bucket in listOf("week", "month")) {
            val res = get("/api/v1/analytics/series?from=2026-10-01&to=2026-10-31&bucket=$bucket")
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(bucket, Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonObject["bucket"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `only one of from and to is a 400 naming the missing one`() = analyticsTest {
        val onlyTo = get("/api/v1/analytics/categories?to=2026-10-31")
        assertEquals(HttpStatusCode.BadRequest, onlyTo.status)
        assertTrue("from: give both" in onlyTo.bodyAsText())
        assertTrue("to: give both" in get("/api/v1/analytics/merchants?from=2026-10-01").bodyAsText())
    }

    @Test
    fun `analytics DTOs round-trip and require every field`() {
        val period = """{"from":"2026-10-01","to":"2026-10-31"}"""
        val money = """{"spent":"1.00","received":"2.00","net":"1.00"}"""
        assertRoundTrips(SummaryResponse.serializer(), """{"period":$period,"totals":$money,"spendCount":1,"previousPeriod":$period,"previous":$money,"spentChangePct":null}""")
        assertRoundTrips(SeriesResponse.serializer(), """{"period":$period,"bucket":"day","points":[{"start":"2026-10-01","spent":"1.00","received":"0.00"}]}""")
        assertRoundTrips(CategoriesResponse.serializer(), """{"period":$period,"categories":[{"category":"food","spent":"1.00","share":1.0,"count":1,"previousSpent":"0.00"}]}""")
        assertRoundTrips(MerchantsResponse.serializer(), """{"period":$period,"merchants":[{"merchant":"Swiggy","spent":"1.00","count":1,"category":"food"}]}""")
        for (s in listOf(PeriodDto.serializer(), MoneyDto.serializer(), SummaryResponse.serializer(), SeriesPointDto.serializer(), SeriesResponse.serializer(),
            CategoryDto.serializer(), CategoriesResponse.serializer(), MerchantDto.serializer(), MerchantsResponse.serializer())) {
            assertRejectsMissingFields(s)
        }
    }
}
