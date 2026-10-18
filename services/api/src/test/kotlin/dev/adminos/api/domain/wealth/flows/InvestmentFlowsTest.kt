package dev.adminos.api.domain.wealth.flows

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.evaluation.CaseInput
import dev.adminos.api.domain.financial.evaluation.GoldenSet
import dev.adminos.api.domain.financial.evaluation.LabelledCase
import dev.adminos.api.domain.financial.evaluation.PipelineLabeller
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.pipeline.PipelineContext
import dev.adminos.api.domain.ingestion.pipeline.StepResult
import dev.adminos.api.domain.wealth.InMemoryInvestmentLedger
import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.WealthFixtures
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-815: investment flows are their own nature (not spend, not income), measured on golden rows, and matched to the ledger. */
class InvestmentFlowsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val labeller = PipelineLabeller()
    private val step = InvestmentFlowStep(InvestmentFlowRules.load())
    private val user = UUID.randomUUID()

    private data class NatureCase(val case: LabelledCase, val nature: TransactionNature, val subcategory: String?)

    private fun natureGolden(): List<NatureCase> = javaClass.getResource("/wealth/investment-flows-golden.csv")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("id,") }
        .map { line ->
            val f = line.split(',').map { it.trim() }
            NatureCase(LabelledCase(f[0], CaseInput(f[1].ifEmpty { null }, f[2].ifEmpty { null }, f[3].ifEmpty { null }?.let { PaymentMethod.valueOf(it) },
                TransactionType.valueOf(f[4])), dev.adminos.api.domain.financial.evaluation.Label(TransactionCategory.OTHER, null), emptySet()),
                TransactionNature.valueOf(f[5]), f[6].ifEmpty { null })
        }

    @Test
    fun `every investment golden row is an investment and every other row is not`() = runBlocking<Unit> {
        val wrong = natureGolden().mapNotNull { c ->
            val t = labeller.transactionFor(c.case)
            val subOk = c.subcategory == null || t.subcategory == c.subcategory
            if (t.nature != c.nature || !subOk) "${c.case.id}: ${t.nature}/${t.subcategory}, expected ${c.nature}/${c.subcategory}" else null
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `on the D-61 golden set only the investment-tagged rows become investments`() = runBlocking<Unit> {
        val golden = GoldenSet.load()
        val investments = golden.filter { c -> labeller.transactionFor(c).nature == TransactionNature.INVESTMENT }.map { it.id }.toSet()
        val tagged = golden.filter { "investment" in it.tags }.map { it.id }.toSet()
        assertEquals(tagged, investments)
        assertTrue(tagged.size >= 4)
    }

    private fun txn(type: TransactionType, amount: String, raw: String, at: String = "2025-02-10T09:00:00Z", category: TransactionCategory = TransactionCategory.OTHER,
                    source: String = "rules", nature: TransactionNature = TransactionNature.defaultFor(type)) =
        Transaction(userId = user, type = type, amount = BigDecimal(amount), merchantRaw = raw, transactedAt = Instant.parse(at), category = category,
            categorySource = source, nature = nature)

    private suspend fun stepped(t: Transaction): Transaction {
        val ctx = PipelineContext(event = IngestEvent(userId = user, source = dev.adminos.api.domain.ingestion.events.IngestSource.SMS, sourceRef = "x",
            occurredAt = t.transactedAt, direction = dev.adminos.api.domain.ingestion.events.IngestDirection.DEBIT, amount = t.amount), transaction = t)
        return ((step.apply(ctx) as StepResult.Continue).context.txn)
    }

    @Test
    fun `the owner's own category, transfers and refunds are left alone`() = runBlocking<Unit> {
        assertEquals(TransactionNature.SPEND, stepped(txn(TransactionType.DEBIT, "1000", "GROWW SIP", category = TransactionCategory.SHOPPING, source = "user")).nature)
        assertEquals(TransactionNature.TRANSFER, stepped(txn(TransactionType.DEBIT, "1000", "GROWW SIP", nature = TransactionNature.TRANSFER)).nature)
        assertEquals(TransactionNature.REFUND, stepped(txn(TransactionType.CREDIT, "1000", "GROWW SIP", nature = TransactionNature.REFUND)).nature)
        val known = stepped(txn(TransactionType.DEBIT, "1000", "UPI SIP", category = TransactionCategory.SHOPPING).copy(subcategory = "online"))
        assertEquals(TransactionNature.SPEND, known.nature, "no investment payee in the text")
        val knownCategory = stepped(txn(TransactionType.DEBIT, "1000", "KUVERA SIP", category = TransactionCategory.SUBSCRIPTION).copy(subcategory = "app"))
        assertEquals(TransactionNature.INVESTMENT, knownCategory.nature); assertEquals("app", knownCategory.subcategory, "a known subcategory is kept")
    }

    @Test
    fun `a SIP debit is not spending and a redemption is not income`() = runBlocking<Unit> {
        val txns = InMemoryTransactionRepository()
        txns.save(stepped(txn(TransactionType.DEBIT, "10000.00", "ACH D- EXAMPLE MUTUAL FUND SIP")))
        txns.save(stepped(txn(TransactionType.CREDIT, "5000.00", "NEFT CR-EXAMPLE MUTUAL FUND-REDEMPTION")))
        txns.save(txn(TransactionType.DEBIT, "250.00", "UPI-SWIGGY-ORDER", category = TransactionCategory.FOOD))
        txns.save(txn(TransactionType.CREDIT, "100000.00", "NEFT CR-ACME CORP-SALARY"))
        val summary = AnalyticsService(InMemoryAnalyticsRepository(txns)).summary(user, DateRange(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28)), zone)
        assertEquals(0, BigDecimal("250").compareTo(summary.current.spent), "only the food order is spent")
        assertEquals(0, BigDecimal("100000").compareTo(summary.current.received), "only the salary is received")
    }

    private var n = 0
    private val acct = UUID.randomUUID(); private val fund = UUID.randomUUID()
    private fun ledger(type: InvestmentTxnType, date: String, units: String?, amount: String) = InvestmentTransaction(userId = user, accountId = acct,
        instrumentId = fund, batchId = null, type = type, tradeDate = LocalDate.parse(date), units = units?.let(Quantity::parse), unitPrice = null,
        amount = BigDecimal(amount), sourceRef = WealthFixtures.ref(n++))

    @Test
    fun `a debit matches the purchase plus its stamp duty within the settlement window`() {
        val buy = ledger(InvestmentTxnType.SIP_BUY, "2025-02-11", "48", "9999.50")
        val rows = listOf(buy, ledger(InvestmentTxnType.STAMP_DUTY, "2025-02-11", null, "0.50"))
        val flows = InvestmentFlowService.match(listOf(txn(TransactionType.DEBIT, "10000.00", "MF SIP")), rows, zone)
        assertEquals(FlowStatus.MATCHED, flows.single().status); assertEquals(buy.id, flows.single().match?.id)
        assertEquals(FlowStatus.UNMATCHED, InvestmentFlowService.match(listOf(txn(TransactionType.DEBIT, "9999.50", "MF SIP")), rows, zone).single().status, "to the paisa")
        assertEquals(FlowStatus.UNMATCHED, InvestmentFlowService.match(listOf(txn(TransactionType.DEBIT, "10000.00", "MF SIP", at = "2025-02-01T09:00:00Z")), rows, zone).single().status, "too early")
        assertEquals(FlowStatus.UNMATCHED, InvestmentFlowService.match(listOf(txn(TransactionType.DEBIT, "10000.00", "MF SIP", at = "2025-02-13T09:00:00Z")), rows, zone).single().status, "after the purchase by more than a day")
    }

    @Test
    fun `ties match nothing and one purchase pays one debit only`() {
        val rows = listOf(ledger(InvestmentTxnType.BUY, "2025-02-10", "1", "5000.00"), ledger(InvestmentTxnType.BUY, "2025-02-11", "1", "5000.00"))
        assertEquals(FlowStatus.AMBIGUOUS, InvestmentFlowService.match(listOf(txn(TransactionType.DEBIT, "5000.00", "MF")), rows, zone).single().status)
        val one = listOf(ledger(InvestmentTxnType.BUY, "2025-02-10", "1", "5000.00"))
        val two = InvestmentFlowService.match(listOf(txn(TransactionType.DEBIT, "5000.00", "MF"), txn(TransactionType.DEBIT, "5000.00", "MF", at = "2025-02-10T10:00:00Z")), one, zone)
        assertEquals(listOf(FlowStatus.MATCHED, FlowStatus.UNMATCHED), two.map { it.status })
    }

    @Test
    fun `a redemption credit matches the sale of the same amount just before it`() {
        val sale = ledger(InvestmentTxnType.SELL, "2025-06-13", "-50", "5500.00")
        val flows = InvestmentFlowService.match(listOf(txn(TransactionType.CREDIT, "5500.00", "MF REDEMPTION", at = "2025-06-16T09:00:00Z")), listOf(sale), zone)
        assertEquals(FlowStatus.MATCHED, flows.single().status)
        assertNull(InvestmentFlowService.match(listOf(txn(TransactionType.REFUND, "5500.00", "MF")), listOf(sale), zone).single().match)
    }

    @Test
    fun `the report totals invested and redeemed and counts what needs review`() = runBlocking<Unit> {
        val txns = InMemoryTransactionRepository()
        val ledgerStore = InMemoryInvestmentLedger()
        txns.save(stepped(txn(TransactionType.DEBIT, "10000.00", "ACH D- EXAMPLE MUTUAL FUND SIP")))
        txns.save(stepped(txn(TransactionType.CREDIT, "2000.00", "NEFT CR-EXAMPLE MUTUAL FUND-REDEMPTION")))
        val report = InvestmentFlowService(txns, ledgerStore).report(user, Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-12-31T00:00:00Z"), zone)
        assertEquals(0, BigDecimal("10000").compareTo(report.invested)); assertEquals(0, BigDecimal("2000").compareTo(report.redeemed))
        assertEquals(0, BigDecimal("8000").compareTo(report.net)); assertEquals(2, report.unmatched)
    }
}
