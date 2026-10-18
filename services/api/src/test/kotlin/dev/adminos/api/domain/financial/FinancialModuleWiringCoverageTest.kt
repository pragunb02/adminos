package dev.adminos.api.domain.financial

import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.FinancialCoverageSupport.dataArray
import dev.adminos.api.domain.financial.FinancialCoverageSupport.getAs
import dev.adminos.api.domain.financial.FinancialCoverageSupport.jwtService
import dev.adminos.api.domain.financial.FinancialCoverageSupport.newUser
import dev.adminos.api.domain.financial.FinancialCoverageSupport.str
import dev.adminos.api.domain.financial.FinancialCoverageSupport.tokenFor
import dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository
import dev.adminos.api.domain.financial.links.SuggestionService
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Application.financialModule() resolves its services from Koin and mounts every financial route (AOS-312). */
class FinancialModuleWiringCoverageTest {

    private val user = newUser()
    private val token = tokenFor(user)

    private val transactions = InMemoryTransactionRepository()
    private val accounts = InMemoryAccountRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val bills = InMemoryBillRepository()

    private val financial = module {
        single<TransactionRepository> { transactions }
        single<SubscriptionRepository> { subscriptions }
        single { CategoryCorrectionService(InMemoryCategoryRuleRepository(), transactions) }
        single { AccountDiscoveryService(accounts, transactions) }
        single { BillTrackingService(bills, transactions) }
        single { AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)) }
        single<AnomalyRepository> { InMemoryAnomalyRepository() }
        single<BriefingRepository> { InMemoryBriefingRepository() }
        single<InsightRepository> { InMemoryInsightRepository() }
        single { SuggestionService(InMemoryTransactionLinkRepository(), transactions) }
        single { AnalyticsService(InMemoryAnalyticsRepository(transactions)) }
        single { dev.adminos.api.domain.financial.mandates.MandateService(dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository(), transactions) }
        single { DuesService(bills, subscriptions, get()) }
        single {
            val store = dev.adminos.api.domain.financial.learning.CategoryModelStore(dev.adminos.api.domain.financial.learning.InMemoryCategoryModelRepository())
            val learned = dev.adminos.api.domain.financial.learning.LearnedCategorizer(store, InMemoryCategoryRuleRepository(), transactions)
            dev.adminos.api.domain.financial.learning.CategoryModelTrainingService(transactions, store, learned, emptyList(), enabled = false)
        }
        single<dev.adminos.api.domain.financial.receipts.OrderReceiptRepository> { dev.adminos.api.domain.financial.receipts.InMemoryOrderReceiptRepository() }
        single { dev.adminos.api.domain.financial.receipts.HiddenFeesService(get()) }
        single { dev.adminos.api.domain.financial.receipts.ReceiptMatchingService(get(), transactions) }
        single {
            dev.adminos.api.domain.financial.review.CategoryReviewService(transactions, get(),
                dev.adminos.api.domain.financial.review.InMemoryGoldenCandidateRepository(), get())
        }
        single { dev.adminos.api.domain.forecast.ForecastService(dev.adminos.api.domain.forecast.InMemoryForecastHistoryRepository(transactions, bills), get(), subscriptions) }
        single {
            dev.adminos.api.domain.financial.grouping.MerchantGroupingService(transactions, dev.adminos.api.domain.financial.grouping.InMemoryMerchantAliasRepository(),
                dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository()), get())
        }
    }

    private fun moduleTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            this.install(Koin) { modules(financial) }
            configureSerialization()
            configureRequestId()
            configureErrorHandling()
            configureAuthentication(jwtService)
            financialModule()
        }
        block()
    }

    @Test
    fun `accounts, subscriptions and bills routes read the Koin-provided repositories`() = moduleTest {
        runBlocking {
            accounts.save(Account(userId = user.id, bankCode = "HDFC", bankName = "HDFC Bank", accountLast4 = "4821"))
            subscriptions.save(Subscription(userId = user.id, name = "Netflix", amount = BigDecimal("649"), billingCycle = BillingCycle.MONTHLY))
            bills.save(Bill(userId = user.id, billerName = "Tata Power", amount = BigDecimal("2350.75"), dueDate = LocalDate.now().plusDays(3)))
        }
        assertEquals("4821", getAs(token, "/api/v1/accounts").second.dataArray().single().jsonObject.str("accountLast4"))
        assertEquals("Netflix", getAs(token, "/api/v1/subscriptions").second.dataArray().single().jsonObject.str("name"))
        assertEquals("Tata Power", getAs(token, "/api/v1/bills/upcoming").second.dataArray().single().jsonObject.str("billerName"))
        assertEquals(io.ktor.http.HttpStatusCode.OK, getAs(token, "/api/v1/dues").first, "dues calendar (AOS-503)")
    }

    @Test
    fun `transaction, dashboard, suggestion, analytics and forecast routes are mounted under api v1`() = moduleTest {
        runBlocking {
            transactions.save(Transaction(userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal("450.00"), merchantName = "Swiggy",
                transactedAt = java.time.Instant.now().minusSeconds(3600)))
        }
        assertEquals(1, getAs(token, "/api/v1/transactions").second.dataArray().size)
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/transactions/summary").first)
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/dashboard/home").first)
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/subscriptions/summary").first)
        assertEquals(HttpStatusCode.NotFound, getAs(token, "/api/v1/bills/${UUID.randomUUID()}").first)
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/transactions/suggestions").first)
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/analytics/categories").first)
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/categories/model").first, "learned categoriser status (AOS-707)")
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/categories/review").first, "category questions (AOS-708)")
        assertEquals(HttpStatusCode.OK, getAs(token, "/api/v1/forecast").first)
    }
}
