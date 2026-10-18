package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/** Assignment quality of the trip rule on the synthetic eval set. */
data class TripScores(val precision: Double, val recall: Double, val pairwisePrecision: Double, val pairwiseRecall: Double)

/**
 * The trip grouping rule measured on a synthetic eval set (AOS-910, D-90, after D-61):
 * `src/test/resources/trips/eval-*.csv`, labelled with the trip a careful person would assign.
 * - **Assignment:** each predicted trip is named after the expected trip most of its transactions belong to;
 *   a transaction is right when its predicted trip's name is its expected trip. Precision = right / predicted
 *   in a trip; recall = right / expected in a trip.
 * - **Pairwise:** pairs of transactions in the same trip, predicted vs expected.
 * The test fails when any score drops below `trips/baseline.json`; raise the baseline deliberately, with the change that moved it.
 */
class TripEvaluationTest {
    private val user = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun rows(resource: String): List<List<String>> =
        requireNotNull(javaClass.getResource(resource)) { "missing $resource" }.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map { it.split(',') }

    private fun uuid(label: String): UUID = UUID.nameUUIDFromBytes(label.toByteArray())

    fun score(): TripScores {
        val expected = mutableMapOf<UUID, String>()
        val txns = rows("/trips/eval-transactions.csv").map { r ->
            val id = uuid(r[1])
            if (r.size > 8 && r[8].isNotBlank()) expected[id] = r[8]
            Transaction(id = id, userId = user, sourceType = r[6], type = TransactionType.DEBIT, amount = BigDecimal(r[5]),
                merchantName = r[3], category = TransactionCategory.valueOf(r[4].uppercase()), paymentMethod = PaymentMethod.valueOf(r[7].uppercase()),
                transactedAt = OffsetDateTime.parse(r[2]).toInstant())
        }
        val bookings = rows("/trips/eval-bookings.csv").map { r ->
            TravelBooking(id = uuid(r[1]), userId = user, source = BookingSource.GMAIL, sourceRef = r[1], brand = r[2],
                mode = TravelMode.valueOf(r[3].uppercase()), refHash = TravelBookings.refHash(r[2], r[1].uppercase().padEnd(6, 'X')),
                journeyDate = r[5].takeIf { it.isNotBlank() }?.let(LocalDate::parse), bookedAt = OffsetDateTime.parse(r[4]).toInstant(),
                amount = r[6].takeIf { it.isNotBlank() }?.let(::BigDecimal), cancelled = r[7].toBoolean())
        }
        val trips = TripGrouper().group(TripInput(txns, emptyMap(), bookings, emptyList(), zone))
        val predicted = trips.flatMap { t -> t.members.map { it.transactionId to t.id } }.toMap()
        if (System.getenv("TRIP_EVAL_DEBUG") != null) {
            val names = rows("/trips/eval-transactions.csv").associate { uuid(it[1]) to it[1] }
            trips.forEach { t -> println("TRIP ${t.start}..${t.end}: " + t.members.joinToString { names[it.transactionId].orEmpty() }) }
        }
        val label = trips.associate { t ->
            t.id to (t.members.mapNotNull { expected[it.transactionId] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "none")
        }
        val right = predicted.count { (txn, trip) -> expected[txn] != null && expected[txn] == label[trip] }
        fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b
        val ids = txns.map { it.id }
        val pairs = ids.flatMapIndexed { i, a -> ids.drop(i + 1).map { b -> a to b } }
        val predictedPairs = pairs.filter { (a, b) -> predicted[a] != null && predicted[a] == predicted[b] }.toSet()
        val expectedPairs = pairs.filter { (a, b) -> expected[a] != null && expected[a] == expected[b] }.toSet()
        return TripScores(
            precision = ratio(right, predicted.size), recall = ratio(right, expected.size),
            pairwisePrecision = ratio((predictedPairs intersect expectedPairs).size, predictedPairs.size),
            pairwiseRecall = ratio((predictedPairs intersect expectedPairs).size, expectedPairs.size)
        )
    }

    @Test
    fun `the trip rule meets its committed baseline`() {
        val s = score()
        println("Trip eval (AOS-910): precision %.4f, recall %.4f, pairwise precision %.4f, pairwise recall %.4f"
            .format(s.precision, s.recall, s.pairwisePrecision, s.pairwiseRecall))
        val base = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/trips/baseline.json")).readText()).jsonObject
        val floor = { key: String -> base.getValue(key).jsonPrimitive.double }
        val dropped = listOf("precision" to s.precision, "recall" to s.recall, "pairwisePrecision" to s.pairwisePrecision, "pairwiseRecall" to s.pairwiseRecall)
            .filter { (k, v) -> v + 1e-9 < floor(k) }
        assertTrue(dropped.isEmpty(), "Trip eval dropped below the baseline: $dropped")
    }
}
