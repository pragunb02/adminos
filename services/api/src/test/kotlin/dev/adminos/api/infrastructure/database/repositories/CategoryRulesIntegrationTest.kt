package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.CategoryCorrectionService
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.CategoryRulesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/** AOS-210 on Postgres: V15, rule upsert, payee lookup, correction backfill and the pipeline using the rule. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CategoryRulesIntegrationTest {

    private val events = ExposedIngestEventRepository()
    private val txns = ExposedTransactionRepository()
    private val links = ExposedTransactionLinkRepository()
    private val rules = ExposedCategoryRuleRepository()
    private val corrections = CategoryCorrectionService(rules, txns) { w -> dbQuery { w() } }
    private val processor = IngestProcessor(
        events,
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(rules)))),
        RepositoryOutcomeWriter(txns, events, links) { w -> dbQuery { w() } }
    )

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun event(user: UUID, ref: String, at: Instant, vpa: String?) = IngestEvent(
        userId = user, source = IngestSource.SMS, sourceRef = ref, occurredAt = at.truncatedTo(ChronoUnit.MILLIS),
        direction = IngestDirection.DEBIT, amount = BigDecimal("40.00"), merchantRaw = "UPI-RAMU CHAIWALA", accountHint = "1234",
        paymentMethod = PaymentMethod.UPI, upiVpa = vpa
    )

    private suspend fun drain() { while (processor.processBatch() > 0) {} }

    @Test
    fun `a correction re-labels the past and the pipeline uses it for the next payment`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t0 = Instant.now().minus(5, ChronoUnit.DAYS)
        events.saveNew(listOf(event(user, "a", t0, "ramu@okicici"), event(user, "b", t0.plus(1, ChronoUnit.DAYS), "RAMU@okicici"))); drain()
        val (first, second) = txns.findByUserId(user, limit = 10).sortedBy { it.transactedAt }
        assertEquals(TransactionCategory.OTHER, first.category, "unknown UPI payee is Other, not Transfer")

        val result = corrections.correct(first, TransactionCategory.FOOD, "tea")
        assertEquals(1, result.relabelled)
        assertEquals(TransactionCategory.FOOD, txns.findById(second.id)!!.category)

        events.saveNew(listOf(event(user, "c", t0.plus(2, ChronoUnit.DAYS), "ramu@okicici"))); drain()
        val third = txns.findByUserId(user, limit = 10).maxBy { it.transactedAt }
        assertEquals(TransactionCategory.FOOD, third.category); assertEquals("remembered", third.categorySource)
    }

    @Test
    fun `upsert keeps one rule per payee and the latest category`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        events.saveNew(listOf(event(user, "a", Instant.now().minus(1, ChronoUnit.DAYS), null))); drain()
        val t = txns.findByUserId(user, limit = 1).single()
        corrections.correct(t, TransactionCategory.SHOPPING, null)
        corrections.correct(t, TransactionCategory.FOOD, "tea")
        val rule = rules.findFor(user, null, t.merchantName!!.trim().lowercase())!!
        assertEquals(TransactionCategory.FOOD, rule.category); assertEquals("tea", rule.subcategory)
        val count = dbQuery { CategoryRulesTable.selectAll().where { CategoryRulesTable.userId eq user }.count() }
        assertEquals(1L, count)
    }
}
