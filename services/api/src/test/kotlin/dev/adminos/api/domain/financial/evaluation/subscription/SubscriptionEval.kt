package dev.adminos.api.domain.financial.evaluation.subscription

import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.SubscriptionDetectorService
import dev.adminos.api.domain.financial.SubscriptionStatus
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** One charge in an eval case: [daysAgo] before the eval's today, paid by a mandate when [mandate]. */
data class EvalCharge(val daysAgo: Long, val amount: BigDecimal, val mandate: Boolean) {
    init {
        require(daysAgo >= 0) { "A charge is in the past" }
        require(amount.signum() >= 0) { "A charge is not negative" }
    }
}

/** One merchant's history for one user (AOS-516) and what the detector should say about it today. */
data class SubscriptionCase(
    val id: String,
    val merchantRaw: String,
    val upiVpa: String?,
    val paymentMethod: PaymentMethod?,
    val charges: List<EvalCharge>,
    val expectedSubscription: Boolean,
    val expectedCycle: BillingCycle?,
    val tags: Set<String>
) {
    init {
        require(charges.isNotEmpty()) { "$id has no charges" }
        require(expectedSubscription == (expectedCycle != null)) { "$id: a subscription has a cycle, anything else has none" }
    }
}

/** What the detector said about a case: an active subscription (with its cycle) or none. */
data class SubscriptionOutcome(val case: SubscriptionCase, val predictedCycle: BillingCycle?) {
    val predicted: Boolean get() = predictedCycle != null
}

@Serializable
data class SubscriptionEvalReport(
    val cases: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val trueNegatives: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double,
    /** Of the subscriptions found, the share with the right billing cycle. */
    val cycleAccuracy: Double,
    val misses: List<String>
)

/** The checked-in floor (AOS-516): `./gradlew -q subscriptionEvalReport -PwriteBaseline` re-records it. */
@Serializable
data class SubscriptionBaseline(val cases: Int, val precision: Double, val recall: Double, val f1: Double, val cycleAccuracy: Double)

/**
 * The subscription eval set (AOS-516): synthetic merchant histories, subscriptions and regular
 * shopping, replayed through the real normaliser, categoriser and detector night by night.
 */
object SubscriptionEval {
    const val RESOURCE = "/subscriptions/eval.csv"
    const val BASELINE = "/subscriptions/baseline.json"
    private const val HEADER = "id,merchant_raw,upi_vpa,payment_method,charges,expected,expected_cycle,tags"
    /** The eval's today. */
    val NOW: Instant = Instant.parse("2026-10-16T06:30:00Z")
    val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(resource: String = RESOURCE): List<SubscriptionCase> {
        val text = SubscriptionEval::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("eval set not found: $resource")
        return parse(text)
    }

    fun parse(text: String): List<SubscriptionCase> {
        val lines = text.lines().withIndex().filter { (_, l) -> l.isNotBlank() && !l.startsWith("#") }
        require(lines.firstOrNull()?.value == HEADER) { "the eval set must start with the header $HEADER" }
        val cases = lines.drop(1).map { (i, line) ->
            val c = line.split(",")
            require(c.size == 8) { "line ${i + 1}: 8 columns expected, found ${c.size}" }
            try {
                SubscriptionCase(
                    id = c[0], merchantRaw = c[1], upiVpa = c[2].ifBlank { null },
                    paymentMethod = c[3].ifBlank { null }?.let { PaymentMethod.valueOf(it) },
                    charges = c[4].trim().split(Regex("\\s+")).map(::charge),
                    expectedSubscription = when (c[5]) { "sub" -> true; "not" -> false; else -> throw IllegalArgumentException("expected is sub or not, was '${c[5]}'") },
                    expectedCycle = c[6].ifBlank { null }?.let { BillingCycle.valueOf(it) },
                    tags = c[7].split(" ").filter { it.isNotBlank() }.toSet()
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("line ${i + 1} (${c[0]}): ${e.message}", e)
            }
        }
        val duplicate = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "duplicate case ids $duplicate" }
        return cases
    }

    private fun charge(s: String): EvalCharge {
        val p = s.split(":")
        require(p.size in 2..3 && (p.size == 2 || p[2] == "m")) { "a charge is daysAgo:amount[:m], was '$s'" }
        return EvalCharge(p[0].toLong(), BigDecimal(p[1]), p.size == 3)
    }

    /** Replays [case] night by night (a run the day after each charge, then today) and reads the result. */
    fun run(case: SubscriptionCase, detectorAt: (InMemoryTransactionRepository, InMemorySubscriptionRepository, Clock) -> SubscriptionDetectorService): SubscriptionOutcome = runBlocking {
        val user = UUID.randomUUID()
        val transactions = InMemoryTransactionRepository()
        val subscriptions = InMemorySubscriptionRepository()
        val merchant = MerchantNormalizer().normalize(case.merchantRaw, case.upiVpa)
        val categorised = CategorizationService().builtIn(merchant)
        val charges = case.charges.sortedByDescending { it.daysAgo }
        val nights = (charges.map { (it.daysAgo - 1).coerceAtLeast(0) } + 0L).distinct().sortedDescending()
        var added = 0
        for (night in nights) {
            while (added < charges.size && charges[added].daysAgo >= night) {
                val c = charges[added++]
                transactions.save(Transaction(
                    userId = user, type = TransactionType.DEBIT, amount = c.amount, merchantName = merchant, merchantRaw = case.merchantRaw,
                    upiVpa = case.upiVpa, paymentMethod = case.paymentMethod, category = categorised.category, subcategory = categorised.subcategory,
                    isRecurring = c.mandate, metadata = if (c.mandate) mapOf("mandate" to "true") else null,
                    transactedAt = NOW.minusSeconds(c.daysAgo * 86_400)
                ))
            }
            detectorAt(transactions, subscriptions, Clock.fixed(NOW.minusSeconds(night * 86_400), ZoneOffset.UTC)).detectSubscriptions(user)
        }
        val live = subscriptions.findByUserId(user, limit = 100)
            .filter { it.status == SubscriptionStatus.ACTIVE || it.status == SubscriptionStatus.TRIAL }
            .maxByOrNull { it.lastBilledDate ?: java.time.LocalDate.MIN }
        SubscriptionOutcome(case, live?.billingCycle)
    }

    /** The shipped detector, as wired in the app. */
    fun shipped(transactions: InMemoryTransactionRepository, subscriptions: InMemorySubscriptionRepository, clock: Clock) =
        dev.adminos.api.di.subscriptionDetector(transactions, subscriptions, UserCalendar(InMemoryUserRepository(), clock), LensMatcher())

    fun evaluate(outcomes: List<SubscriptionOutcome>): SubscriptionEvalReport {
        val tp = outcomes.count { it.predicted && it.case.expectedSubscription }
        val fp = outcomes.count { it.predicted && !it.case.expectedSubscription }
        val fn = outcomes.count { !it.predicted && it.case.expectedSubscription }
        val tn = outcomes.size - tp - fp - fn
        val precision = ratio(tp, tp + fp)
        val recall = ratio(tp, tp + fn)
        val f1 = if (precision + recall == 0.0) 0.0 else round(2 * precision * recall / (precision + recall))
        val found = outcomes.filter { it.predicted && it.case.expectedSubscription }
        val cycle = ratio(found.count { it.predictedCycle == it.case.expectedCycle }, found.size)
        val misses = outcomes.mapNotNull { o ->
            val c = o.case
            when {
                o.predicted && !c.expectedSubscription -> "${c.id} false positive (${o.predictedCycle}) ${c.tags.sorted().joinToString(" ")}"
                !o.predicted && c.expectedSubscription -> "${c.id} missed ${c.expectedCycle} ${c.tags.sorted().joinToString(" ")}"
                o.predicted && o.predictedCycle != c.expectedCycle -> "${c.id} wrong cycle ${o.predictedCycle}, expected ${c.expectedCycle}"
                else -> null
            }
        }
        return SubscriptionEvalReport(outcomes.size, tp, fp, fn, tn, precision, recall, f1, cycle, misses)
    }

    fun report(): SubscriptionEvalReport = evaluate(load().map { run(it, ::shipped) })

    fun baseline(resource: String = BASELINE): SubscriptionBaseline {
        val text = SubscriptionEval::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("baseline not found: $resource")
        return json.decodeFromString(SubscriptionBaseline.serializer(), text)
    }

    private fun ratio(a: Int, b: Int): Double = if (b == 0) 0.0 else round(a.toDouble() / b)
    private fun round(d: Double): Double = Math.round(d * 10_000) / 10_000.0
}

/** `./gradlew -q subscriptionEvalReport [-PwriteBaseline]` (AOS-516): prints the numbers; records the floor when asked. */
fun main(args: Array<String>) {
    val report = SubscriptionEval.report()
    println(SubscriptionEval.json.encodeToString(SubscriptionEvalReport.serializer(), report))
    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val file = java.io.File(args[i + 1], "baseline.json")
        file.writeText(SubscriptionEval.json.encodeToString(SubscriptionBaseline.serializer(),
            SubscriptionBaseline(report.cases, report.precision, report.recall, report.f1, report.cycleAccuracy)) + "\n")
        println("baseline written to ${file.path}")
    }
}
