package dev.adminos.api.domain.balances

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Where a balance was read (AOS-720, D-170). Only sources whose sender we can trust record one. */
enum class BalanceSource(val key: String, /** Breaks a tie at the same instant: a statement's closing figure beats an SMS. */ val rank: Int) {
    STATEMENT("statement", 0),
    SMS("sms", 1);

    companion object {
        fun of(key: String): BalanceSource = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("unknown balance source")
    }
}

/** A bank account as the inputs name it: the last four digits, and the bank when the input says which. */
data class BalanceAccount(val bankCode: String?, val last4: String) {
    init {
        require(LAST4.matches(last4)) { "An account is named by exactly 4 digits" }
        require(bankCode == null || BANK.matches(bankCode)) { "A bank code is 1-16 lower-case letters, digits or _" }
    }

    override fun toString() = "BalanceAccount(${bankCode ?: "?"}, ••$last4)"

    companion object {
        private val LAST4 = Regex("\\d{4}")
        private val BANK = Regex("[a-z0-9_]{1,16}")

        /** A bank code from an input ("HDFC", "hdfc") in our form, or null when it isn't one. */
        fun bankCodeOf(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { BANK.matches(it) }
    }
}

/**
 * One balance an input showed for an account at a moment (AOS-720, D-170): an SMS's "Avl Bal" after a transaction,
 * or a statement's closing balance. A reading is a fact about the past, never edited; the latest per account is chosen
 * by [observedAt], so a late or re-sent message can never replace a newer figure.
 *
 * [amount] is sensitive: it's encrypted at rest and never logged ([toString] leaves it out). Negative = overdrawn.
 */
data class BalanceReading(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val account: BalanceAccount,
    val amount: BigDecimal,
    /** When the balance was true: the SMS's time, or the statement's closing day (never after the import). */
    val observedAt: Instant,
    val source: BalanceSource,
    /** Identity of the input (a SHA-256 hex), so the same message or statement records once. */
    val sourceRef: String,
    val recordedAt: Instant
) {
    init {
        require(amount.scale() == 2) { "A balance has 2 decimal places" }
        require(amount.abs() < MAX_ABS) { "A balance is below ₹100 crore either way" }
        require(SOURCE_REF.matches(sourceRef)) { "A source ref is a SHA-256 hex" }
    }

    override fun toString() = "BalanceReading($id, $account, ${source.key}, observedAt=$observedAt)" // never the amount

    companion object {
        /** ₹100 crore: a figure at or beyond this on a personal account is a misread or a forgery. */
        val MAX_ABS: BigDecimal = BigDecimal("1000000000.00")
        private val SOURCE_REF = Regex("[0-9a-f]{64}")

        /** Newest first: the later moment; at the same moment a statement, then the later recorded. */
        val NEWEST_FIRST: Comparator<BalanceReading> = compareByDescending<BalanceReading> { it.observedAt }
            .thenBy { it.source.rank }.thenByDescending { it.recordedAt }.thenBy { it.id }
    }
}

/** How old a latest balance is, in the user's days (D-170). */
enum class Freshness {
    /** Today or yesterday. */
    FRESH,
    /** 2 to [BalanceRules.MAX_AGE_DAYS] days: still counted, and labelled with its date. */
    AGING,
    /** Older: shown with its date, never counted in "money left". */
    STALE;

    val counted: Boolean get() = this != STALE

    companion object {
        fun of(ageDays: Long): Freshness = when {
            ageDays <= BalanceRules.FRESH_DAYS -> FRESH
            ageDays <= BalanceRules.MAX_AGE_DAYS -> AGING
            else -> STALE
        }
    }
}

/**
 * An account's latest balance, as of [observedOn] in the user's zone, [ageDays] before today. [account] names the
 * bank when any of its readings did (an SMS from a generic sender and a statement can name the same account).
 */
data class AccountBalance(val account: BalanceAccount, val reading: BalanceReading, val observedOn: LocalDate, val ageDays: Long) {
    init {
        require(ageDays >= 0) { "A latest balance isn't from the future" }
    }

    val freshness: Freshness get() = Freshness.of(ageDays)
    override fun toString() = "AccountBalance($account, $observedOn, $freshness)"
}

/** Every account's latest balance, and the total of those recent enough to count. */
data class BalanceOverview(val today: LocalDate, val accounts: List<AccountBalance>) {
    val counted: List<AccountBalance> get() = accounts.filter { it.freshness.counted }
    val total: BigDecimal get() = counted.fold(BigDecimal.ZERO.setScale(2)) { acc, a -> acc + a.reading.amount }
    override fun toString() = "BalanceOverview($today, ${accounts.size} accounts)"
}

object BalanceRules {
    /** Today and yesterday are "fresh". */
    const val FRESH_DAYS = 1L
    /** A balance older than this many days is stale: shown, never counted (the forecast's limit). */
    const val MAX_AGE_DAYS = 7L
    /** A reading more than this far in the future (a device clock or a forgery) is refused. */
    val FUTURE_SLACK: java.time.Duration = java.time.Duration.ofMinutes(10)
    /** Readings kept per account; older ones are deleted (only the latest is ever used). */
    const val KEEP_PER_ACCOUNT = 20
}
