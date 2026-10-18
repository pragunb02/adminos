package dev.adminos.api.domain.wealth

import dev.adminos.api.infrastructure.crypto.IdentifierKind
import dev.adminos.api.infrastructure.crypto.WealthIdentifiers
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Synthetic wealth data for tests (AOS-802): invented folios and amounts, ISINs in the `INF…` shape with made-up
 * characters. The key is derived at test time from a public seed, so no secret is written in the repo.
 */
object WealthFixtures {
    /** A test master key: SHA-256 of a public seed, base64 (the same shape as `TOKEN_ENCRYPTION_KEY`). */
    fun testKey(seed: String = "adminos-wealth-test-seed"): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()))

    val identifiers = WealthIdentifiers(testKey())

    /** A number in 10000..89999 for instruments in the shared integration database. */
    fun uniqueN(): Int = Math.floorMod(System.nanoTime(), 80000L).toInt() + 10000

    /** A made-up ISIN for scheme [n] (shape-valid, not a real security). */
    fun isin(n: Int) = "INF" + "ZZ9" + n.toString().padStart(5, '0') + "0"

    fun ref(vararg parts: Any?): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray()))

    fun account(userId: UUID, folio: String = "90000001", institution: String = "Example Mutual Fund", id: UUID = UUID.randomUUID()) =
        InvestmentAccount(id = id, userId = userId, kind = InvestmentAccountKind.MF_FOLIO, institution = institution,
            identifier = identifiers.protect(IdentifierKind.FOLIO, folio, "investment_accounts:$id:identifier"), createdAt = Instant.parse("2026-10-01T00:00:00Z"))

    fun instrument(n: Int = 1, amfi: String? = "10000$n", name: String = "Example Flexi Cap Fund - Direct Plan - Growth") =
        Instrument(name = name, assetClass = AssetClass.EQUITY_IN, isin = isin(n), amfiCode = amfi, mfPlan = MfPlan.DIRECT,
            createdAt = Instant.parse("2026-10-01T00:00:00Z"))

    fun batch(userId: UUID, events: Int = 0) =
        ImportBatch(userId = userId, readerId = "casparser", readerVersion = "1.4.1", statementFrom = LocalDate.of(2025, 1, 1),
            statementTo = LocalDate.of(2025, 12, 31), events = events, added = 0, duplicates = 0, createdAt = Instant.parse("2026-10-02T00:00:00Z"))

    fun buy(
        userId: UUID, account: InvestmentAccount, instrument: Instrument, batch: ImportBatch?, date: LocalDate, units: String, nav: String, amount: String,
        type: InvestmentTxnType = InvestmentTxnType.BUY, occurrence: Int = 0
    ) = InvestmentTransaction(
        userId = userId, accountId = account.id, instrumentId = instrument.id, batchId = batch?.id, type = type, tradeDate = date,
        units = Quantity.parse(units), unitPrice = UnitPrice.parse(nav), amount = BigDecimal(amount),
        sourceRef = ref(account.identifier.blindIndex, instrument.isin, date, type, units, amount, occurrence),
        createdAt = Instant.parse("2026-10-02T00:00:00Z")
    )
}
