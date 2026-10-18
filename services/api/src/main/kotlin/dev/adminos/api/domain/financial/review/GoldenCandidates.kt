package dev.adminos.api.domain.financial.review

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A scrubbed, golden-set-shaped copy of one answered transaction (AOS-708), kept in the user's own database as their
 * **local** golden set. It holds a cleaned business name in a synthetic narration, the payment method, the direction
 * and the category the user chose: no amount, date, reference, account or card number, UPI ID or message text.
 * It is never committed: Pragun reviews the exported rows by hand before any of them goes into the repo's golden.csv.
 */
data class GoldenCandidate(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val merchantRaw: String,
    val paymentMethod: PaymentMethod?,
    val direction: TransactionType,
    val category: TransactionCategory,
    val createdAt: Instant
) {
    init {
        require(merchantRaw.isNotBlank() && merchantRaw.length <= GoldenScrubber.MAX_RAW && ',' !in merchantRaw) { "A scrubbed narration has no commas" }
        require(direction == TransactionType.DEBIT || direction == TransactionType.CREDIT) { "A golden row is a debit or a credit" }
        require(category != TransactionCategory.TRANSFER) { "Transfers are never added" }
    }

    /** The golden-set id: "L" for local, and a stable part of this row's id. */
    val goldenId: String get() = "L" + id.toString().replace("-", "").take(10).uppercase()

    /** The row in `golden.csv` format (D-61): id, narration, UPI ID (blank), method, direction, category, subcategory (blank), tags. */
    fun csvLine(): String = listOf(goldenId, merchantRaw, "", paymentMethod?.name.orEmpty(), direction.name, category.name, "", "real-shaped local").joinToString(",")
}

/** Why a transaction can't be added to the local golden set automatically. */
enum class ScrubRefusal {
    /** No cleaned payee name to keep. */
    NO_NAME,
    /** It looks like a payment to a person (a phone-number UPI ID, or answered as Other: rent, a friend): add by hand. */
    LOOKS_LIKE_A_PERSON,
    /** Transfers and income aren't categorised by the rules being measured. */
    NOT_SPENDING
}

sealed interface ScrubResult {
    data class Scrubbed(val candidate: GoldenCandidate) : ScrubResult
    data class Refused(val reason: ScrubRefusal) : ScrubResult
}

/**
 * Turns an answered transaction into a [GoldenCandidate] (AOS-708), following AOS-511's "growing the golden set" rules
 * mechanically: keep the cleaned business name; drop every digit, the UPI ID, the amount and the date; never take a
 * payment that looks like one to a person. Pure. A person's name can't always be recognised, so the export is
 * reviewed by hand before anything is committed.
 */
object GoldenScrubber {
    const val MAX_RAW = 120
    private val PHONE_HANDLE = Regex("^\\+?\\d{10,12}@")
    private val NOT_KEPT = Regex("[^A-Z&' ]+")
    private val SPACES = Regex("\\s+")
    private val NATURES = setOf(TransactionNature.SPEND, TransactionNature.REFUND)

    fun scrub(t: Transaction, category: TransactionCategory, at: Instant): ScrubResult {
        if (t.nature !in NATURES || category == TransactionCategory.TRANSFER) return ScrubResult.Refused(ScrubRefusal.NOT_SPENDING)
        if (category == TransactionCategory.OTHER || t.upiVpa?.trim()?.let { PHONE_HANDLE.containsMatchIn(it) } == true) {
            return ScrubResult.Refused(ScrubRefusal.LOOKS_LIKE_A_PERSON)
        }
        val name = cleanName(t.merchantName) ?: return ScrubResult.Refused(ScrubRefusal.NO_NAME)
        val direction = if (t.nature == TransactionNature.REFUND) TransactionType.CREDIT else TransactionType.DEBIT
        return ScrubResult.Scrubbed(GoldenCandidate(userId = t.userId, merchantRaw = narration(name, t.paymentMethod), paymentMethod = t.paymentMethod,
            direction = direction, category = category, createdAt = at))
    }

    /** Upper case letters, spaces, `&` and `'` only (no digits, so no phone numbers or references); null if under 2 letters. */
    fun cleanName(name: String?): String? =
        name?.uppercase()?.replace(NOT_KEPT, " ")?.replace(SPACES, " ")?.trim()?.take(60)?.trim()?.takeIf { n -> n.count { it.isLetter() } >= 2 }

    /** A synthetic narration in the shape the bank would use, which the pipeline cleans back to [name]. */
    private fun narration(name: String, method: PaymentMethod?): String = when (method) {
        PaymentMethod.UPI -> "UPI-$name-PAYMENT"
        PaymentMethod.CARD_CREDIT, PaymentMethod.CARD_DEBIT -> "POS 400000XXXXXX0000 $name"
        else -> name
    }
}

/** The user's local golden set (AOS-708): InMemory for dev and tests, Exposed on Postgres. */
interface GoldenCandidateRepository {
    /** Adds the row unless the same narration, method, direction and category is already there; true when added. */
    suspend fun add(candidate: GoldenCandidate): Boolean
    suspend fun list(userId: UUID): List<GoldenCandidate>
    suspend fun delete(userId: UUID, id: UUID): Boolean
}

class InMemoryGoldenCandidateRepository : GoldenCandidateRepository {
    private val rows = ConcurrentHashMap<UUID, GoldenCandidate>()

    override suspend fun add(candidate: GoldenCandidate): Boolean = synchronized(rows) {
        val duplicate = rows.values.any {
            it.userId == candidate.userId && it.merchantRaw == candidate.merchantRaw && it.paymentMethod == candidate.paymentMethod &&
                it.direction == candidate.direction && it.category == candidate.category
        }
        if (!duplicate) rows[candidate.id] = candidate
        !duplicate
    }

    override suspend fun list(userId: UUID): List<GoldenCandidate> =
        rows.values.filter { it.userId == userId }.sortedWith(compareBy<GoldenCandidate> { it.createdAt }.thenBy { it.id })

    override suspend fun delete(userId: UUID, id: UUID): Boolean = rows[id]?.takeIf { it.userId == userId }?.let { rows.remove(id) } != null
}
