package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Where shared expenses come from (D-111): the CSV export now; the Splitwise API can be a second adapter. */
enum class SplitSource { SPLITWISE_CSV }

/** A Splitwise row is either a shared expense or a settle-up ("payment") between two people. */
enum class SplitKind { EXPENSE, PAYMENT }

/**
 * What a shared entry does to the owner's money (D-111). Decided once from the owner's numbers:
 * - [ADJUST_DEBIT]: the owner paid the whole bill; their bank debit counts only their share.
 * - [OWED_SPEND]: a friend paid; the owner's share is a spend they owe (no bank record yet).
 * - [SETTLE_OUT] / [SETTLE_IN]: a settle-up the owner paid / received; the UPI transfer is a settlement.
 * - [NONE]: nothing to do (the owner wasn't in it, or paid exactly their share).
 */
enum class SplitEffect { ADJUST_DEBIT, OWED_SPEND, SETTLE_OUT, SETTLE_IN, NONE;
    val needsMatch: Boolean get() = this == ADJUST_DEBIT || this == SETTLE_OUT || this == SETTLE_IN
}

/**
 * Where an entry stands. Only [APPLIED] changes any total; every other state leaves the transactions as they were.
 * - [SUGGESTED]: a likely transaction is waiting for the owner in Review.
 * - [UNMATCHED]: no transaction yet; retried when a new one arrives.
 * - [IGNORED]: the owner said to leave it; [NO_EFFECT]: nothing to apply.
 */
enum class SplitState { APPLIED, SUGGESTED, UNMATCHED, IGNORED, NO_EFFECT }

/**
 * One shared entry as read from a source, before it is stored (the port every source adapter produces).
 * [ownerNet] is the change to the owner's Splitwise balance: positive when they paid more than their share
 * (friends owe them), negative when they owe. [counterparty] is the other person in a settle-up, when exactly one.
 */
data class SharedEntry(
    val sourceRef: String,
    val kind: SplitKind,
    val date: LocalDate,
    val description: String,
    val category: String?,
    val cost: BigDecimal,
    val currency: String,
    val ownerNet: BigDecimal,
    val counterparty: String? = null
) {
    init {
        require(sourceRef.isNotBlank() && sourceRef.length <= 64) { "a source reference is 1–64 characters" }
        require(cost.signum() > 0 && cost.scale() <= 2) { "a cost is positive with at most 2 decimals" }
        require(ownerNet.scale() <= 2 && ownerNet.abs() <= cost) { "the owner's balance change can't exceed the cost" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "a currency is a 3-letter code" }
    }

    /** The owner's share of the cost. A settle-up has no share. */
    val ownerShare: BigDecimal get() = when {
        kind == SplitKind.PAYMENT -> BigDecimal.ZERO.setScale(2)
        ownerNet.signum() > 0 -> (cost - ownerNet).setScale(2) // paid it all; others owe the rest
        else -> ownerNet.negate().setScale(2)                   // paid nothing; owes this much
    }

    val effect: SplitEffect get() = when {
        ownerNet.signum() == 0 -> SplitEffect.NONE
        kind == SplitKind.PAYMENT -> if (ownerNet.signum() > 0) SplitEffect.SETTLE_OUT else SplitEffect.SETTLE_IN
        ownerNet.signum() > 0 -> SplitEffect.ADJUST_DEBIT
        else -> SplitEffect.OWED_SPEND
    }

    /** The bank amount to look for: the whole bill for an expense the owner paid, else the settle-up amount. */
    val bankAmount: BigDecimal get() = if (effect == SplitEffect.ADJUST_DEBIT) cost.setScale(2) else ownerNet.abs().setScale(2)
}

/** What a transaction looked like before an entry was applied, so undo restores it exactly. */
data class PriorState(
    val nature: TransactionNature,
    val category: TransactionCategory,
    val subcategory: String?,
    val categorySource: String
)

/** A stored entry and its state (V90). [description] and [counterparty] are encrypted at rest by the repository. */
data class SplitEntry(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val importId: UUID,
    val source: SplitSource,
    val sourceRef: String,
    val kind: SplitKind,
    val effect: SplitEffect,
    val state: SplitState,
    val date: LocalDate,
    val description: String,
    val counterparty: String?,
    val category: String?,
    val cost: BigDecimal,
    val currency: String,
    val ownerNet: BigDecimal,
    val ownerShare: BigDecimal,
    /** The matched (or suggested) bank transaction, or the spend created for [SplitEffect.OWED_SPEND]. */
    val transactionId: UUID? = null,
    val createdTransaction: Boolean = false,
    val prior: PriorState? = null,
    /** Transactions the owner said are not this entry: never suggested again. */
    val rejected: Set<UUID> = emptySet(),
    val score: Int? = null,
    val reason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(state != SplitState.APPLIED || transactionId != null) { "an applied entry names its transaction" }
        require(state != SplitState.SUGGESTED || transactionId != null) { "a suggestion names its transaction" }
        require(ownerShare.signum() >= 0 && ownerShare <= cost) { "a share is between 0 and the cost" }
    }
}

data class SplitImport(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val source: SplitSource,
    val rowsRead: Int,
    val createdAt: Instant = Instant.now()
)
