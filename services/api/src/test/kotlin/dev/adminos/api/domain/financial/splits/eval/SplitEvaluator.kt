package dev.adminos.api.domain.financial.splits.eval

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.splits.InMemorySplitRepository
import dev.adminos.api.domain.financial.splits.SplitState
import dev.adminos.api.domain.financial.splits.SplitService
import dev.adminos.api.domain.financial.splits.SplitwiseCsv
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** The eval's numbers (AOS-911). Money is exact strings; ratios have 4 decimals. */
@Serializable
data class SplitEvalReport(
    val seed: Int,
    val cases: Int,
    /** Entries that need a bank record (bills the owner paid, settle-ups), and how many have one in the data. */
    val matchable: Int,
    val withBankRecord: Int,
    val appliedCorrect: Int,
    val appliedWrong: Int,
    val suggestedCorrect: Int,
    val suggestedWrong: Int,
    val missed: Int,
    /** Applied/suggested though the data has no bank record for it. */
    val falseMatches: Int,
    /** Of the matches applied without asking, the share that are right. */
    val autoPrecision: Double,
    /** Of the entries with a bank record, the share applied without asking. */
    val autoRecall: Double,
    /** Of the entries with a bank record, the share applied or put in Review with the right record. */
    val reviewRecall: Double,
    val friendPaidSharesCorrect: Int,
    val friendPaidShares: Int,
    val trueSpent: String,
    /** What the console shows with no Splitwise import (every bill counted in full, paybacks as income). */
    val spentWithoutSplitwise: String,
    val spentAfterImport: String,
    /** After the owner confirms right suggestions and rejects wrong ones. */
    val spentAfterReview: String,
    val errorWithoutSplitwise: String,
    val errorAfterImport: String,
    val errorAfterReview: String,
    val receivedWithoutSplitwise: String,
    val receivedAfterImport: String,
    val byKind: Map<String, String>
)

object SplitEvaluator {
    private val OCT = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    suspend fun run(seed: Int = SplitEvalSet.SEED): SplitEvalReport {
        val user = UUID(0, 911)
        val set = SplitEvalSet.build(user, seed)
        val txns = InMemoryTransactionRepository()
        set.bank.forEach { txns.save(it) }
        val analytics = AnalyticsService(InMemoryAnalyticsRepository(txns))
        val before = analytics.summary(user, OCT, SplitEvalSet.ZONE).current

        val repo = InMemorySplitRepository()
        val service = SplitService(repo, txns, CategorizationService(), UserCalendar(InMemoryUserRepository()))
        service.importCsv(user, set.csv, set.owner)
        val afterImport = analytics.summary(user, OCT, SplitEvalSet.ZONE).current

        val refs = SplitwiseCsv.parse(set.csv, set.owner).entries.map { it.sourceRef }
        check(refs.size == set.cases.size) { "every eval row must be readable" }
        val entries = repo.findBySourceRefs(user, refs).associateBy { it.sourceRef }
        val rows = set.cases.zip(refs).map { (c, ref) -> c to entries.getValue(ref) }
        val matchable = rows.filter { (_, e) -> e.effect.needsMatch && e.currency == "INR" }
        val withTruth = matchable.filter { it.first.truthTransactionId != null }
        fun count(state: SplitState, right: Boolean) = matchable.count { (c, e) -> e.state == state && c.truthTransactionId != null && (e.transactionId == c.truthTransactionId) == right }
        val appliedCorrect = count(SplitState.APPLIED, true)
        val appliedWrong = matchable.count { (c, e) -> e.state == SplitState.APPLIED && e.transactionId != c.truthTransactionId }
        val suggestedCorrect = count(SplitState.SUGGESTED, true)
        val suggestedWrong = matchable.count { (c, e) -> e.state == SplitState.SUGGESTED && e.transactionId != c.truthTransactionId }
        val missed = withTruth.count { it.second.state == SplitState.UNMATCHED }
        val falseMatches = matchable.count { (c, e) -> c.truthTransactionId == null && e.state in setOf(SplitState.APPLIED, SplitState.SUGGESTED) }
        val friendPaid = rows.filter { it.first.kind == CaseKind.FRIEND_PAID }
        val friendPaidRight = friendPaid.count { (c, e) -> e.state == SplitState.APPLIED && e.transactionId?.let { txns.findById(it) }?.amount?.compareTo(c.trueSpend) == 0 }

        // The owner works through Review: confirm the right record, reject a wrong one (the next best is then offered).
        repeat(10) {
            for ((c, e) in rows) {
                val current = repo.findById(e.id) ?: continue
                if (current.state != SplitState.SUGGESTED) continue
                if (current.transactionId == c.truthTransactionId) service.confirm(user, current.id) else service.reject(user, current.id)
            }
        }
        val afterReview = analytics.summary(user, OCT, SplitEvalSet.ZONE).current

        val truth = set.trueSpent
        fun err(x: BigDecimal) = (x - truth).abs().setScale(2).toPlainString()
        fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else BigDecimal(a).divide(BigDecimal(b), 4, RoundingMode.HALF_UP).toDouble()
        val byKind = rows.groupBy { it.first.kind }.mapValues { (_, list) ->
            list.groupingBy { it.second.state.name.lowercase() }.eachCount().toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
        }.mapKeys { it.key.name.lowercase() }.toSortedMap()
        return SplitEvalReport(
            seed = seed, cases = set.cases.size, matchable = matchable.size, withBankRecord = withTruth.size,
            appliedCorrect = appliedCorrect, appliedWrong = appliedWrong, suggestedCorrect = suggestedCorrect, suggestedWrong = suggestedWrong,
            missed = missed, falseMatches = falseMatches,
            autoPrecision = ratio(appliedCorrect, appliedCorrect + appliedWrong), autoRecall = ratio(appliedCorrect, withTruth.size),
            reviewRecall = ratio(appliedCorrect + suggestedCorrect, withTruth.size),
            friendPaidSharesCorrect = friendPaidRight, friendPaidShares = friendPaid.size,
            trueSpent = truth.setScale(2).toPlainString(), spentWithoutSplitwise = before.spent.setScale(2).toPlainString(),
            spentAfterImport = afterImport.spent.setScale(2).toPlainString(), spentAfterReview = afterReview.spent.setScale(2).toPlainString(),
            errorWithoutSplitwise = err(before.spent), errorAfterImport = err(afterImport.spent), errorAfterReview = err(afterReview.spent),
            receivedWithoutSplitwise = before.received.setScale(2).toPlainString(), receivedAfterImport = afterImport.received.setScale(2).toPlainString(),
            byKind = byKind
        )
    }
}
