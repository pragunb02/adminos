package dev.adminos.api.domain.wealth.flows

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.ingestion.pipeline.IngestStep
import dev.adminos.api.domain.ingestion.pipeline.PipelineContext
import dev.adminos.api.domain.ingestion.pipeline.StepResult
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Which bank narrations move money to or from investments (AOS-815, D-79), from `resources/wealth/investment-flows.json`
 * (data, D-44 style). Validated on load: a bad file stops start-up.
 */
class InvestmentFlowRules(val version: String, payees: List<Payee>, dividend: List<String>) {
    data class Payee(val name: String, val patterns: List<Regex>)

    val payees: List<Payee> = payees.also { require(it.isNotEmpty() && it.all { p -> p.patterns.isNotEmpty() }) { "investment flows: every payee needs patterns" } }
    private val dividend: List<Regex> = dividend.map { Regex(it, RegexOption.IGNORE_CASE) }

    /** The payee this transaction's text names, or null. */
    fun payeeOf(t: Transaction): Payee? = text(t).let { s -> payees.firstOrNull { p -> p.patterns.any { it.containsMatchIn(s) } } }
    fun isDividend(t: Transaction): Boolean = text(t).let { s -> dividend.any { it.containsMatchIn(s) } }

    private fun text(t: Transaction) = listOfNotNull(t.merchantRaw, t.merchantName, t.upiVpa).joinToString(" ").take(MAX_TEXT)

    companion object {
        private const val MAX_TEXT = 1_000
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable private data class FilePayee(val name: String, val patterns: List<String>)
        @Serializable private data class RulesFile(val version: String, val dividend: List<String>, val payees: List<FilePayee>)

        fun load(resource: String = "/wealth/investment-flows.json"): InvestmentFlowRules {
            val text = InvestmentFlowRules::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("investment flow rules not found: $resource")
            val file = json.decodeFromString(RulesFile.serializer(), text)
            return InvestmentFlowRules(file.version, file.payees.map { p -> Payee(p.name, p.patterns.map { Regex(it, RegexOption.IGNORE_CASE) }) }, file.dividend)
        }
    }
}

/**
 * The bank-feed side of investing (AOS-815, D-79). Runs after [dev.adminos.api.domain.ingestion.pipeline.TransferStep]:
 * - a debit or credit to or from a fund house, an MF platform, a clearing corporation, a broker, NPS, PPF or an FD is
 *   nature `INVESTMENT`: money moving between the owner's own assets, so neither spent nor received (D-39, D-46), and
 *   the savings rate counts a SIP as saved, not spent (AOS-908);
 * - a dividend credit from them stays income;
 * - an own-account transfer, a refund or a reversal is left alone, and so is a category the owner chose.
 * Only the nature changes: the category and subcategory stay what the categoriser decided, so the D-61 category
 * metrics (and the AI eval records replayed on them) are untouched; the nature has its own golden gate.
 */
class InvestmentFlowStep(private val rules: InvestmentFlowRules) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val t = context.txn
        if (t.categorySource == "user" || t.nature !in setOf(TransactionNature.SPEND, TransactionNature.INCOME)) return StepResult.Continue(context)
        rules.payeeOf(t) ?: return StepResult.Continue(context)
        val next = when {
            t.type == TransactionType.CREDIT && rules.isDividend(t) -> t // a dividend is income (D-79)
            t.type == TransactionType.DEBIT || t.type == TransactionType.CREDIT -> t.copy(nature = TransactionNature.INVESTMENT)
            else -> t
        }
        return StepResult.Continue(context.copy(transaction = next))
    }
}

enum class FlowStatus { MATCHED, UNMATCHED, AMBIGUOUS; val wire: String get() = name.lowercase() }

/** A bank investment debit or credit, and the ledger row it paid for (or was paid by), if exactly one fits. */
data class InvestmentFlow(val transaction: Transaction, val status: FlowStatus, val match: InvestmentTransaction?)

data class InvestmentFlowsReport(val flows: List<InvestmentFlow>, val invested: BigDecimal, val redeemed: BigDecimal) {
    val net: BigDecimal get() = invested - redeemed
    val unmatched: Int get() = flows.count { it.status != FlowStatus.MATCHED }
}

/**
 * Links bank investment flows to ledger rows (AOS-815, WEALTH-RESEARCH §4.3), computed on read so it is always in step
 * with the ledger and undo:
 * - a debit pays a purchase (buy, SIP, switch in) when debit = purchase amount + that day's stamp duty, to the paisa,
 *   with the purchase from one day before to [PURCHASE_DAYS_AFTER] days after the debit;
 * - a credit is a sale (redemption, switch out) of the same amount from [SALE_DAYS_BEFORE] days before to the credit day;
 * - each ledger row pays at most one bank row; ties match nothing (D-38/D-54 style) and are shown for review.
 */
class InvestmentFlowService(private val transactions: TransactionRepository, private val ledger: InvestmentLedger) {

    suspend fun report(userId: UUID, from: Instant, to: Instant, zone: ZoneId): InvestmentFlowsReport {
        val bank = transactions.findByUserId(userId, from = from, to = to, nature = TransactionNature.INVESTMENT, limit = MAX_ROWS)
            .sortedBy { it.transactedAt }
        val rows = ledger.active(userId)
        val flows = match(bank, rows, zone)
        val invested = bank.filter { it.type == TransactionType.DEBIT }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        val redeemed = bank.filter { it.type == TransactionType.CREDIT }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        return InvestmentFlowsReport(flows, invested, redeemed)
    }

    companion object {
        const val PURCHASE_DAYS_AFTER = 4L
        const val SALE_DAYS_BEFORE = 5L
        const val MAX_ROWS = 2_000
        private val BUYS = setOf(InvestmentTxnType.BUY, InvestmentTxnType.SIP_BUY, InvestmentTxnType.SWITCH_IN)
        private val SALES = setOf(InvestmentTxnType.SELL, InvestmentTxnType.SWITCH_OUT)

        /** Pure matching (tested on its own). */
        fun match(bank: List<Transaction>, rows: List<InvestmentTransaction>, zone: ZoneId): List<InvestmentFlow> {
            val stamps = rows.filter { it.type == InvestmentTxnType.STAMP_DUTY }
                .groupBy { Triple(it.accountId, it.instrumentId, it.tradeDate) }
                .mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, r -> a + (r.amountInr ?: BigDecimal.ZERO) } }
            fun paid(r: InvestmentTransaction) = (r.amountInr ?: BigDecimal.ZERO) + (stamps[Triple(r.accountId, r.instrumentId, r.tradeDate)] ?: BigDecimal.ZERO)
            val used = mutableSetOf<UUID>()
            return bank.map { t ->
                val day = t.transactedAt.atZone(zone).toLocalDate()
                val candidates = rows.filter { r -> r.id !in used && fits(t, r, day, ::paid) }
                when (candidates.size) {
                    1 -> InvestmentFlow(t, FlowStatus.MATCHED, candidates.single()).also { used += candidates.single().id }
                    0 -> InvestmentFlow(t, FlowStatus.UNMATCHED, null)
                    else -> InvestmentFlow(t, FlowStatus.AMBIGUOUS, null)
                }
            }
        }

        private fun fits(t: Transaction, r: InvestmentTransaction, day: LocalDate, paid: (InvestmentTransaction) -> BigDecimal): Boolean {
            val gap = ChronoUnit.DAYS.between(day, r.tradeDate)
            return when (t.type) {
                TransactionType.DEBIT -> r.type in BUYS && gap in -1..PURCHASE_DAYS_AFTER && paid(r).compareTo(t.amount) == 0
                TransactionType.CREDIT -> r.type in SALES && gap in -SALE_DAYS_BEFORE..0 && (r.amountInr ?: BigDecimal.ZERO).compareTo(t.amount) == 0
                else -> false
            }
        }
    }
}
