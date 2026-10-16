package dev.adminos.api.domain.financial

import dev.adminos.api.domain.financial.FinancialCoverageSupport.data
import dev.adminos.api.domain.financial.FinancialCoverageSupport.dataArray
import dev.adminos.api.domain.financial.FinancialCoverageSupport.errorCode
import dev.adminos.api.domain.financial.FinancialCoverageSupport.getAs
import dev.adminos.api.domain.financial.FinancialCoverageSupport.isNull
import dev.adminos.api.domain.financial.FinancialCoverageSupport.money
import dev.adminos.api.domain.financial.FinancialCoverageSupport.newUser
import dev.adminos.api.domain.financial.FinancialCoverageSupport.pagination
import dev.adminos.api.domain.financial.FinancialCoverageSupport.routeTest
import dev.adminos.api.domain.financial.FinancialCoverageSupport.str
import dev.adminos.api.domain.financial.FinancialCoverageSupport.tokenFor
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Disabled
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/subscriptions list, summary and detail (AOS-312). */
class FinancialSubscriptionRoutesCoverageTest {

    private val user = newUser()
    private val token = tokenFor(user)
    private val subscriptions = InMemorySubscriptionRepository()
    private val base = Instant.parse("2026-10-01T04:30:00Z")

    private fun subTest(block: suspend ApplicationTestBuilder.() -> Unit) = routeTest({ subscriptionRoutes(subscriptions) }, block)

    private fun sub(
        name: String, amount: String, cycle: BillingCycle = BillingCycle.MONTHLY,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE, category: SubscriptionCategory = SubscriptionCategory.ENTERTAINMENT,
        flagged: Boolean = false, owner: UUID = user.id, createdMinutesAgo: Long = 0
    ) = Subscription(userId = owner, name = name, merchantName = name.lowercase(), amount = BigDecimal(amount), billingCycle = cycle,
        status = status, category = category, isFlagged = flagged, createdAt = base.minusSeconds(createdMinutesAgo * 60))

    private suspend fun ApplicationTestBuilder.summary(): JsonObject {
        val (status, body) = getAs(token, "/api/v1/subscriptions/summary")
        assertEquals(HttpStatusCode.OK, status)
        return body.data()
    }

    @Test
    fun `list returns my subscriptions newest first, filtered by status`() = subTest {
        runBlocking {
            subscriptions.save(sub("Netflix", "649", createdMinutesAgo = 10))
            subscriptions.save(sub("Spotify", "119", createdMinutesAgo = 5))
            subscriptions.save(sub("Hotstar", "299", status = SubscriptionStatus.CANCELLED))
            subscriptions.save(sub("Zee5", "99", owner = UUID.randomUUID()))
        }
        val (_, all) = getAs(token, "/api/v1/subscriptions")
        assertEquals(listOf("Hotstar", "Spotify", "Netflix"), all.dataArray().map { it.jsonObject.str("name") })
        val (_, active) = getAs(token, "/api/v1/subscriptions?status=active")
        assertEquals(listOf("Spotify", "Netflix"), active.dataArray().map { it.jsonObject.str("name") })
        assertEquals(2, active.pagination()["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a full page returns a cursor that fetches the next page`() = subTest {
        runBlocking { (1..3).forEach { subscriptions.save(sub("Sub $it", "99", createdMinutesAgo = it.toLong())) } }
        val (_, page1) = getAs(token, "/api/v1/subscriptions?limit=2")
        assertEquals(true, page1.pagination()["hasMore"]!!.jsonPrimitive.boolean)
        val cursor = page1.pagination().str("cursor")
        val (_, page2) = getAs(token, "/api/v1/subscriptions?limit=2&cursor=${cursor.encodeURLParameter()}")
        assertEquals(listOf("Sub 3"), page2.dataArray().map { it.jsonObject.str("name") })
        assertTrue(page2.pagination().isNull("cursor"))
    }

    @Test
    fun `summary converts every billing cycle to a monthly cost of active subscriptions only`() = subTest {
        runBlocking {
            subscriptions.save(sub("Daily news", "10", BillingCycle.DAILY, category = SubscriptionCategory.NEWS))           // 300.00
            subscriptions.save(sub("Cult.fit weekly", "100", BillingCycle.WEEKLY, category = SubscriptionCategory.FITNESS)) // 433.00
            subscriptions.save(sub("Netflix", "649", BillingCycle.MONTHLY))                                                // 649.00
            subscriptions.save(sub("Hotstar", "900", BillingCycle.QUARTERLY))                                              // 300.00
            subscriptions.save(sub("Google One", "1200", BillingCycle.YEARLY, category = SubscriptionCategory.CLOUD))      // 100.00
            subscriptions.save(sub("SonyLIV", "999", status = SubscriptionStatus.CANCELLED))
        }
        val s = summary()
        assertEquals(0, BigDecimal("1782.00").compareTo(s.money("totalMonthlyCost")))
        assertEquals("INR", s.str("currency"))
        assertEquals(5, s["activeCount"]!!.jsonPrimitive.int)
        assertEquals(1, s["cancelledCount"]!!.jsonPrimitive.int)
        val byCategory = s["byCategory"]!!.jsonObject
        assertEquals(setOf("news", "fitness", "entertainment", "cloud"), byCategory.keys)
        assertEquals(2, byCategory["entertainment"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(0, BigDecimal("949").compareTo(byCategory["entertainment"]!!.jsonObject.money("monthly")))
        assertEquals(0, BigDecimal("433.00").compareTo(byCategory["fitness"]!!.jsonObject.money("monthly")))
    }

    @Test
    fun `summary counts flagged subscriptions`() = subTest {
        runBlocking {
            subscriptions.save(sub("Netflix", "649", flagged = true))
            subscriptions.save(sub("Spotify", "119"))
        }
        assertEquals(1, summary()["flaggedCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `summary with no subscriptions is all zeros`() = subTest {
        val s = summary()
        assertEquals(0, BigDecimal.ZERO.compareTo(s.money("totalMonthlyCost")))
        assertEquals(0, s["activeCount"]!!.jsonPrimitive.int)
        assertTrue(s["byCategory"]!!.jsonObject.isEmpty())
    }
    @Test
    fun `category monthly cost is an exact decimal`() = subTest {
        runBlocking {
            subscriptions.save(sub("Disney+ Hotstar", "899", BillingCycle.QUARTERLY)) // 299.67 a month
            subscriptions.save(sub("Netflix", "649"))
        }
        val monthly = summary()["byCategory"]!!.jsonObject["entertainment"]!!.jsonObject["monthly"]!!.jsonPrimitive.content
        assertEquals("948.67", monthly)
    }

    @Test
    fun `detail returns my subscription with dates, price change and transactions`() = subTest {
        val txnIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val saved = runBlocking {
            subscriptions.save(sub("Netflix", "649").copy(
                nextBillingDate = LocalDate.of(2026, 11, 5), lastBilledDate = LocalDate.of(2026, 10, 5), firstBilledDate = LocalDate.of(2026, 8, 5),
                priceChanged = true, priceChangePct = BigDecimal("30.06"), isFlagged = true, flaggedReason = "Price up 30%", transactionIds = txnIds))
        }
        val (status, body) = getAs(token, "/api/v1/subscriptions/${saved.id}")
        assertEquals(HttpStatusCode.OK, status)
        val d = body.data()
        assertEquals("monthly", d.str("billingCycle"))
        assertEquals("entertainment", d.str("category"))
        assertEquals("active", d.str("status"))
        assertEquals("2026-11-05", d.str("nextBillingDate"))
        assertEquals("2026-10-05", d.str("lastBilledDate"))
        assertEquals("2026-08-05", d.str("firstBilledDate"))
        assertEquals(0, BigDecimal("30.06").compareTo(d.money("priceChangePct")))
        assertEquals(txnIds.map { it.toString() }, d["transactionIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `detail of a new subscription has null dates and price change`() = subTest {
        val saved = runBlocking { subscriptions.save(sub("Spotify", "119")) }
        val d = getAs(token, "/api/v1/subscriptions/${saved.id}").second.data()
        assertTrue(d.isNull("nextBillingDate") && d.isNull("lastBilledDate") && d.isNull("firstBilledDate") && d.isNull("priceChangePct"))
    }

    @Test
    fun `another user's subscription is a 404`() = subTest {
        val theirs = runBlocking { subscriptions.save(sub("Netflix", "649", owner = UUID.randomUUID())) }
        val (status, body) = getAs(token, "/api/v1/subscriptions/${theirs.id}")
        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals("SUB_001", body.errorCode())
    }

    @Test
    fun `an unknown subscription id is a 404`() = subTest {
        assertEquals(HttpStatusCode.NotFound, getAs(token, "/api/v1/subscriptions/${UUID.randomUUID()}").first)
    }

    @Test
    fun `a malformed subscription id is a 400`() = subTest {
        assertEquals(HttpStatusCode.BadRequest, getAs(token, "/api/v1/subscriptions/netflix").first)
    }
}
