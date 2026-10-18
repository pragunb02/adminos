package dev.adminos.api.domain.financial.splits.eval

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** What a case is (AOS-911 eval). Each kind stresses one rule of the matcher or one way the data can go wrong. */
enum class CaseKind {
    /** The owner paid by UPI the same day; the narration may or may not name the merchant. */
    OWNER_PAID_SAME_DAY,
    /** The owner paid by card; it posts 1–3 days later. */
    OWNER_PAID_CARD_LATE,
    /** Two debits of the same amount in the window: only one is the bill. */
    OWNER_PAID_AMBIGUOUS,
    /** The bill's bank record isn't in AdminOS (that account's statement not imported). */
    OWNER_PAID_NO_BANK,
    /** The owner paid part and a friend the rest: the CSV can't say how much the owner paid (a known gap). */
    OWNER_PAID_MIXED,
    /** A friend paid; the owner's share is owed. */
    FRIEND_PAID,
    /** A friend paid the owner back by UPI; the narration names them. */
    SETTLE_IN_NAMED,
    /** The owner paid a friend back by UPI to the friend's VPA. */
    SETTLE_OUT_NAMED,
    /** A settle-up whose bank record doesn't name the friend. */
    SETTLE_UNNAMED,
    /** A named settle-up with a same-amount UPI payment to someone else the same day. */
    SETTLE_WITH_DECOY,
    /** A row between two friends, or a split the owner paid exactly their share of. */
    NOT_INVOLVED,
    /** A trip expense in another currency. */
    OTHER_CURRENCY
}

/**
 * One case: the Splitwise row(s), the bank records, and the truth.
 * [truthTransactionId] is the bank record the entry is really about (null when there is none in AdminOS).
 * [trueSpend] is what the case really cost the owner (for the period's true total).
 */
data class EvalCase(
    val kind: CaseKind,
    val csvRow: String,
    val bank: List<Transaction>,
    val truthTransactionId: UUID?,
    val trueSpend: BigDecimal
)

data class EvalSet(val header: String, val owner: String, val cases: List<EvalCase>, val background: List<Transaction>) {
    val csv: ByteArray get() = (listOf(header) + cases.map { it.csvRow }).joinToString("\n").toByteArray()
    val bank: List<Transaction> get() = background + cases.flatMap { it.bank }
    /** The owner's real spending in the period: background spends plus each case's true cost. */
    val trueSpent: BigDecimal get() = background.filter { it.type == TransactionType.DEBIT }.fold(BigDecimal.ZERO) { a, t -> a + t.amount } +
        cases.fold(BigDecimal.ZERO) { a, c -> a + c.trueSpend }
}

/**
 * A synthetic, seeded eval set (AOS-911, D-111): Splitwise rows and bank records for one month in Bengaluru, with
 * made-up friends and Indian merchants. No real data (public repo). Deterministic: the same seed gives the same set,
 * including transaction ids, so the committed baseline is reproducible.
 */
object SplitEvalSet {
    const val SEED = 911
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private const val OWNER = "Dev P."
    private val FRIENDS = listOf("Asha R.", "Kabir M.", "Riya S.", "Neel T.")
    private val FULL = mapOf("Asha R." to "ASHA RAO", "Kabir M." to "KABIR MEHTA", "Riya S." to "RIYA SINGH", "Neel T." to "NEEL TIWARI")
    private val VPA = mapOf("Asha R." to "asharao@okicici", "Kabir M." to "kabir.mehta@oksbi", "Riya S." to "riyas22@ybl", "Neel T." to "neeltiwari@okaxis")
    private val STRANGERS = listOf("SURESH KUMAR", "PRIYA NAIR", "MOHAN DAS")
    private data class Place(val merchant: String, val category: TransactionCategory, val splitwise: String, val what: String)
    private val PLACES = listOf(
        Place("Toit Brewpub", TransactionCategory.FOOD, "Dining out", "Dinner"), Place("Truffles", TransactionCategory.FOOD, "Dining out", "Lunch"),
        Place("Meghana Foods", TransactionCategory.FOOD, "Dining out", "Biryani"), Place("Third Wave Coffee", TransactionCategory.FOOD, "Dining out", "Coffee"),
        Place("BigBasket", TransactionCategory.FOOD, "Groceries", "Groceries"), Place("Uber", TransactionCategory.TRANSPORT, "Taxi", "Cab"),
        Place("PVR Cinemas", TransactionCategory.ENTERTAINMENT, "Movies", "Movie"), Place("Airbnb", TransactionCategory.OTHER, "Hotel", "Stay"),
        Place("Decathlon", TransactionCategory.SHOPPING, "General", "Football")
    )
    private val COUNTS = linkedMapOf(
        CaseKind.OWNER_PAID_SAME_DAY to 12, CaseKind.OWNER_PAID_CARD_LATE to 8, CaseKind.OWNER_PAID_AMBIGUOUS to 4,
        CaseKind.OWNER_PAID_NO_BANK to 3, CaseKind.OWNER_PAID_MIXED to 2, CaseKind.FRIEND_PAID to 8,
        CaseKind.SETTLE_IN_NAMED to 5, CaseKind.SETTLE_OUT_NAMED to 4, CaseKind.SETTLE_UNNAMED to 3,
        CaseKind.SETTLE_WITH_DECOY to 3, CaseKind.NOT_INVOLVED to 3, CaseKind.OTHER_CURRENCY to 2
    )

    fun build(user: UUID, seed: Int = SEED): EvalSet {
        val rnd = Random(seed)
        fun id() = UUID(rnd.nextLong(), rnd.nextLong())
        fun day() = LocalDate.of(2026, 10, rnd.nextInt(2, 25))
        fun at(d: LocalDate, hour: Int = rnd.nextInt(9, 22)) = d.atTime(hour, rnd.nextInt(0, 60)).atZone(ZONE).toInstant()
        fun debit(amount: BigDecimal, d: LocalDate, merchant: String?, raw: String?, method: PaymentMethod, category: TransactionCategory = TransactionCategory.OTHER,
                  vpa: String? = null, source: String = "statement") = Transaction(
            id = id(), userId = user, sourceType = source, type = TransactionType.DEBIT, amount = amount, merchantName = merchant, merchantRaw = raw,
            paymentMethod = method, upiVpa = vpa, category = category, transactedAt = at(d)
        )
        fun credit(amount: BigDecimal, d: LocalDate, merchant: String?, raw: String?) = Transaction(
            id = id(), userId = user, sourceType = "sms", type = TransactionType.CREDIT, amount = amount, merchantName = merchant, merchantRaw = raw,
            paymentMethod = PaymentMethod.UPI, transactedAt = at(d)
        )
        val members = listOf(OWNER) + FRIENDS
        fun row(d: LocalDate, description: String, category: String, cost: BigDecimal, currency: String, nets: Map<String, BigDecimal>) =
            (listOf(d.toString(), description, category, cost.toPlainString(), currency) + members.map { (nets[it] ?: BigDecimal.ZERO).setScale(2).toPlainString() }).joinToString(",")

        /** Equal split of [cost] among [people]; [payer] paid it all. Returns each person's net and the owner's share. */
        fun split(cost: BigDecimal, payer: String, people: List<String>): Pair<Map<String, BigDecimal>, BigDecimal> {
            val share = cost.divide(BigDecimal(people.size), 2, java.math.RoundingMode.DOWN)
            val nets = people.associateWith { share.negate() }.toMutableMap()
            val remainder = cost - share.multiply(BigDecimal(people.size))
            nets[payer] = (nets[payer] ?: BigDecimal.ZERO) + cost
            val first = people.first { it != payer }
            nets[first] = nets.getValue(first) - remainder // the first other person carries the rounding
            val ownerShare = if (OWNER == first) share + remainder else if (OWNER in people) share else BigDecimal.ZERO
            return nets to ownerShare.setScale(2)
        }
        fun cost(people: Int) = BigDecimal(people * rnd.nextInt(150, 1500)).setScale(2)
        fun people() = listOf(OWNER) + FRIENDS.shuffled(rnd).take(rnd.nextInt(1, 4))

        val cases = mutableListOf<EvalCase>()
        for ((kind, n) in COUNTS) repeat(n) {
            val place = PLACES[rnd.nextInt(PLACES.size)]
            val d = day()
            val described = if (rnd.nextBoolean()) "${place.what} at ${place.merchant}" else place.what
            when (kind) {
                CaseKind.OWNER_PAID_SAME_DAY, CaseKind.OWNER_PAID_CARD_LATE, CaseKind.OWNER_PAID_AMBIGUOUS, CaseKind.OWNER_PAID_NO_BANK -> {
                    val who = people(); val c = cost(who.size)
                    val (nets, share) = split(c, OWNER, who)
                    val (postDay, method, merchant) = when (kind) {
                        CaseKind.OWNER_PAID_CARD_LATE -> Triple(d.plusDays(rnd.nextLong(1, 4)), PaymentMethod.CARD_CREDIT, place.merchant.uppercase().take(10))
                        else -> Triple(d, PaymentMethod.UPI, if (rnd.nextBoolean()) place.merchant else "Paytm Merchant")
                    }
                    val bill = debit(c, postDay, merchant, "UPI/$merchant", method, if (rnd.nextBoolean()) place.category else TransactionCategory.OTHER)
                    val bank = when (kind) {
                        CaseKind.OWNER_PAID_NO_BANK -> emptyList()
                        CaseKind.OWNER_PAID_AMBIGUOUS -> listOf(bill, debit(c, d.plusDays(rnd.nextLong(0, 2)), "Amazon", "AMAZON PAY", PaymentMethod.CARD_DEBIT, TransactionCategory.SHOPPING))
                        else -> listOf(bill)
                    }
                    // A bill missing from the bank still cost the owner their share (paid from an account not in AdminOS).
                    // The ambiguous case's other debit is a real, separate purchase: it counts in full.
                    val decoySpend = if (kind == CaseKind.OWNER_PAID_AMBIGUOUS) c else BigDecimal.ZERO
                    cases += EvalCase(kind, row(d, described, place.splitwise, c, "INR", nets), bank, bank.firstOrNull()?.id, share + decoySpend)
                }
                CaseKind.OWNER_PAID_MIXED -> {
                    val friend = FRIENDS[rnd.nextInt(FRIENDS.size)]
                    val c = cost(2)
                    val ownerPaid = c.multiply(BigDecimal("0.75")).setScale(2, java.math.RoundingMode.HALF_UP)
                    val share = c.divide(BigDecimal(2), 2, java.math.RoundingMode.HALF_UP)
                    val nets = mapOf(OWNER to ownerPaid - share, friend to share - ownerPaid)
                    val bill = debit(ownerPaid, d, place.merchant, "UPI/${place.merchant}", PaymentMethod.UPI, place.category)
                    cases += EvalCase(kind, row(d, described, place.splitwise, c, "INR", nets), listOf(bill), bill.id, share)
                }
                CaseKind.FRIEND_PAID -> {
                    val who = people(); val payer = who.drop(1).first(); val c = cost(who.size)
                    val (nets, share) = split(c, payer, who)
                    cases += EvalCase(kind, row(d, described, place.splitwise, c, "INR", nets), emptyList(), null, share)
                }
                CaseKind.SETTLE_IN_NAMED, CaseKind.SETTLE_OUT_NAMED, CaseKind.SETTLE_UNNAMED, CaseKind.SETTLE_WITH_DECOY -> {
                    val friend = FRIENDS[rnd.nextInt(FRIENDS.size)]
                    val amount = BigDecimal(rnd.nextInt(2, 40) * 50).setScale(2)
                    val incoming = kind == CaseKind.SETTLE_IN_NAMED || (kind != CaseKind.SETTLE_OUT_NAMED && rnd.nextBoolean())
                    val nets = if (incoming) mapOf(OWNER to amount.negate(), friend to amount) else mapOf(OWNER to amount, friend to amount.negate())
                    val bankDay = d.plusDays(rnd.nextLong(0, 2))
                    val named = kind != CaseKind.SETTLE_UNNAMED
                    val txn = when {
                        incoming -> credit(amount, bankDay, if (named) FULL.getValue(friend) else "UPI Credit", if (named) "UPI/CR/${FULL.getValue(friend)}" else "UPI/CR/REF${rnd.nextInt(100000)}")
                        else -> debit(amount, bankDay, if (named) null else "UPI Payment", "UPI/DR/${if (named) "TRANSFER" else "REF"}", PaymentMethod.UPI,
                            vpa = if (named) VPA.getValue(friend) else null, source = "sms")
                    }
                    val decoy = if (kind == CaseKind.SETTLE_WITH_DECOY) {
                        val stranger = STRANGERS[rnd.nextInt(STRANGERS.size)]
                        if (incoming) credit(amount, bankDay, stranger, "UPI/CR/$stranger") else debit(amount, bankDay, stranger, "UPI/DR/$stranger", PaymentMethod.UPI, source = "sms")
                    } else null
                    // A decoy payment out is real spending; one in is real income (not counted in spent).
                    val decoySpend = if (decoy != null && !incoming) amount else BigDecimal.ZERO
                    cases += EvalCase(kind, row(d, "Payment", "Payment", amount, "INR", nets), listOfNotNull(txn, decoy), txn.id, decoySpend)
                }
                CaseKind.NOT_INVOLVED -> {
                    val (a, b) = FRIENDS.shuffled(rnd).take(2)
                    val c = cost(2)
                    val (nets, _) = split(c, a, listOf(a, b))
                    cases += EvalCase(kind, row(d, described, place.splitwise, c, "INR", nets), emptyList(), null, BigDecimal.ZERO)
                }
                CaseKind.OTHER_CURRENCY -> {
                    val who = people(); val c = BigDecimal(who.size * rnd.nextInt(10, 60)).setScale(2)
                    val (nets, _) = split(c, OWNER, who)
                    // Paid on a forex card that isn't in AdminOS: no INR record and no INR spend to count.
                    cases += EvalCase(kind, row(d, described, place.splitwise, c, "EUR", nets), emptyList(), null, BigDecimal.ZERO)
                }
            }
        }
        val background = (1..30).map {
            val p = PLACES[rnd.nextInt(PLACES.size)]
            debit(BigDecimal(rnd.nextInt(80, 3000)).setScale(2), day(), p.merchant, "UPI/${p.merchant}", PaymentMethod.UPI, p.category, source = "sms")
        }
        val header = "Date,Description,Category,Cost,Currency," + members.joinToString(",")
        return EvalSet(header, OWNER, cases.sortedBy { it.csvRow.substring(0, 10) }, background)
    }
}
