package dev.adminos.api.domain.wealth

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * The wealth model (AOS-802, D-79): accounts → instruments → an append-only ledger of typed transactions.
 * Holdings and lots are derived from the ledger (AOS-811), never stored as the truth.
 */

/** What kind of holding account. Investment accounts are their own model, not a bank `account_type` (WEALTH-RESEARCH §0). */
enum class InvestmentAccountKind {
    MF_FOLIO, DEMAT, BROKER_FOREIGN, EQUITY_AWARD, EPF, NPS, PPF, FD, CRYPTO, GOLD_PHYSICAL, PROPERTY, LOAN_GIVEN, OTHER;
    val wire: String get() = name.lowercase()
    companion object { fun fromWire(s: String) = entries.first { it.wire == s } }
}

/**
 * An account identifier (folio, BO ID, account number…) as it is stored: AES-256-GCM ciphertext bound to its row
 * ([ciphertext]), a keyed blind index for equality lookups ([blindIndex], HMAC-SHA-256 hex), and the last 4
 * characters for display ([last4]). The plain value never sits in the database or in a domain object at rest.
 */
data class ProtectedIdentifier(val ciphertext: String, val blindIndex: String, val last4: String) {
    init {
        require(ciphertext.startsWith("v2:")) { "identifier must be encrypted (TokenEncryptor v2)" }
        require(blindIndex.matches(HEX64)) { "blind index must be 64 hex characters" }
        require(last4.length <= 4) { "last4 is at most 4 characters" }
    }
    /** "••••5678": never more than the last 4. */
    val masked: String get() = "••••$last4"
}

data class InvestmentAccount(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val kind: InvestmentAccountKind,
    /** The AMC, broker or depository as printed (public names such as "Example Mutual Fund"). */
    val institution: String,
    val currency: Currency = INR,
    val identifier: ProtectedIdentifier,
    /** The owner's share of a jointly held account, (0, 1]. */
    val ownerShare: BigDecimal = BigDecimal.ONE,
    /** The bank account that funds it, when known (AOS-815 matching). */
    val fundingAccountId: UUID? = null,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(institution.isNotBlank() && institution.length <= 120) { "institution must be 1–120 characters" }
        require(ownerShare.signum() > 0 && ownerShare <= BigDecimal.ONE) { "owner share must be in (0, 1]" }
    }
}

/** Asset classes are what guidance talks about (D-79: classes, never securities). */
enum class AssetClass {
    EQUITY_IN, EQUITY_FOREIGN, DEBT, HYBRID, GOLD, REAL_ESTATE, CASH_LIKE, CRYPTO, RETIREMENT, OTHER;
    val wire: String get() = name.lowercase()
    companion object { fun fromWire(s: String) = entries.first { it.wire == s } }
}

enum class MfPlan { DIRECT, REGULAR; val wire: String get() = name.lowercase() }

/**
 * A security or scheme. Public reference data (ISIN, AMFI code, ticker), shared and not encrypted (D-79).
 * At least one identifier is needed so prices can be matched to it.
 */
data class Instrument(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val assetClass: AssetClass,
    val isin: String? = null,
    val amfiCode: String? = null,
    val exchange: String? = null,
    val ticker: String? = null,
    val currency: Currency = INR,
    val mfPlan: MfPlan? = null,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(name.isNotBlank() && name.length <= 255) { "instrument name must be 1–255 characters" }
        require(isin != null || amfiCode != null || ticker != null) { "an instrument needs an ISIN, an AMFI code or a ticker" }
        isin?.let { require(it.matches(ISIN)) { "not an ISIN" } }
        amfiCode?.let { require(it.matches(AMFI_CODE)) { "an AMFI scheme code is 1–10 digits" } }
        ticker?.let { require(it.length in 1..20 && it.matches(TICKER)) { "not a ticker" } }
        exchange?.let { require(it.length in 1..10 && it.matches(TICKER)) { "not an exchange code" } }
    }

    companion object {
        val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}\\d")
        val AMFI_CODE = Regex("\\d{1,10}")
        private val TICKER = Regex("[A-Z0-9.&_-]+")
    }
}

/** How a transaction moves units. */
enum class UnitsEffect { IN, OUT, NONE, EITHER }

/**
 * Ledger transaction types (D-79). MF types come first (AOS-805); the others are ready for later importers.
 * [cashFlow] is the investor's cash direction for returns (AOS-812): -1 money in, +1 money out to the investor,
 * 0 no cash (reinvestment, splits, bonuses, transfers of units).
 */
enum class InvestmentTxnType(val units: UnitsEffect, val cashFlow: Int) {
    BUY(UnitsEffect.IN, -1),
    SIP_BUY(UnitsEffect.IN, -1),
    SELL(UnitsEffect.OUT, +1),
    SWITCH_IN(UnitsEffect.IN, -1),
    SWITCH_OUT(UnitsEffect.OUT, +1),
    /** A scheme merger: not a transfer for tax; cost and dates carry over (WEALTH-RESEARCH §2.3). */
    MERGER_IN(UnitsEffect.IN, 0),
    MERGER_OUT(UnitsEffect.OUT, 0),
    DIVIDEND_PAYOUT(UnitsEffect.NONE, +1),
    DIVIDEND_REINVEST(UnitsEffect.IN, 0),
    SPLIT(UnitsEffect.EITHER, 0),
    BONUS(UnitsEffect.IN, 0),
    /** Units created in a segregated portfolio (side pocket). */
    SEGREGATION(UnitsEffect.IN, 0),
    TRANSFER_IN(UnitsEffect.IN, 0),
    TRANSFER_OUT(UnitsEffect.OUT, 0),
    /** A bounced SIP or cancelled purchase: undoes an earlier row. */
    REVERSAL(UnitsEffect.EITHER, +1),
    STAMP_DUTY(UnitsEffect.NONE, -1),
    STT(UnitsEffect.NONE, -1),
    TDS(UnitsEffect.NONE, -1),
    /** Informational rows (nominee registered, address changed): kept for the trail, no money, no units. */
    MISC(UnitsEffect.NONE, 0);

    val wire: String get() = name.lowercase()
    companion object { fun fromWire(s: String) = entries.first { it.wire == s } }
}

/**
 * One ledger row (append-only, D-79). A correction is a new row that [supersedesId] the old one; nothing is
 * updated in place, so imports can be replayed and every number has a trail.
 *
 * [units] is the signed change in holding (+ in, − out). [amount] is the gross money value in [currency], never
 * negative; its direction comes from [type]. A foreign amount also carries [fx] and the [inrAmount] at that rate.
 */
data class InvestmentTransaction(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val accountId: UUID,
    val instrumentId: UUID,
    val batchId: UUID?,
    val type: InvestmentTxnType,
    val tradeDate: LocalDate,
    val units: Quantity?,
    val unitPrice: UnitPrice?,
    val amount: BigDecimal?,
    val currency: Currency = INR,
    val fx: FxRate? = null,
    val inrAmount: BigDecimal? = null,
    /** Idempotency key for re-import: a hash over the row's identity (never over a raw identifier). */
    val sourceRef: String,
    val supersedesId: UUID? = null,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(sourceRef.matches(HEX64)) { "source ref must be 64 hex characters" }
        amount?.let {
            require(it.signum() >= 0) { "amount is never negative; the type gives the direction" }
            require(it.stripTrailingZeros().scale() <= 2) { "amount has at most 2 decimal places" }
            require(it.precision() - it.scale() <= 16) { "amount is too large" }
        }
        inrAmount?.let { require(it.signum() >= 0 && it.stripTrailingZeros().scale() <= 2) { "INR amount: ≥ 0, 2 places" } }
        when (type.units) {
            UnitsEffect.IN -> require(units != null && units.signum > 0) { "${type.wire} adds units" }
            UnitsEffect.OUT -> require(units != null && units.signum < 0) { "${type.wire} removes units" }
            UnitsEffect.NONE -> require(units == null || units.isZero) { "${type.wire} doesn't change units" }
            UnitsEffect.EITHER -> Unit
        }
        if (currency == INR) require(fx == null) { "an INR amount has no FX rate" }
        fx?.let { require(it.base == currency && it.quote == INR) { "FX converts the transaction currency to INR" } }
    }

    /** The amount in rupees: [amount] itself for INR, else the converted [inrAmount]. */
    val amountInr: BigDecimal? get() = if (currency == INR) amount else inrAmount
}

enum class ImportStatus { APPLIED, UNDONE; val wire: String get() = name.lowercase() }

/** One import (a file the owner uploaded): its rows can be undone together (D-79). Counts only, never content. */
data class ImportBatch(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val readerId: String,
    val readerVersion: String,
    val statementFrom: LocalDate?,
    val statementTo: LocalDate?,
    val status: ImportStatus = ImportStatus.APPLIED,
    val events: Int,
    val added: Int,
    val duplicates: Int,
    val createdAt: Instant = Instant.now(),
    val undoneAt: Instant? = null
) {
    init {
        require(readerId.matches(READER) && readerVersion.length in 1..40) { "reader id and version are short codes" }
        require(events >= 0 && added >= 0 && duplicates >= 0 && added + duplicates <= events) { "batch counts don't add up" }
    }
}

internal val HEX64 = Regex("[0-9a-f]{64}")
private val READER = Regex("[a-z0-9_.-]{1,40}")
