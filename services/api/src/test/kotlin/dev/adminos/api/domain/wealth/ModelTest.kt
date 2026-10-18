package dev.adminos.api.domain.wealth

import dev.adminos.api.domain.wealth.WealthFixtures.account
import dev.adminos.api.domain.wealth.WealthFixtures.buy
import dev.adminos.api.domain.wealth.WealthFixtures.instrument
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** AOS-802: the model refuses impossible rows, so an importer bug can't write them. */
class ModelTest {
    private val user = UUID.randomUUID()
    private val acct = account(user)
    private val fund = instrument()
    private val day = LocalDate.of(2025, 4, 10)

    private fun row(type: InvestmentTxnType, units: String?, amount: String? = "100.00", currency: Currency = INR, fx: FxRate? = null) =
        InvestmentTransaction(userId = user, accountId = acct.id, instrumentId = fund.id, batchId = null, type = type, tradeDate = day,
            units = units?.let(Quantity::parse), unitPrice = null, amount = amount?.let(::BigDecimal), currency = currency, fx = fx,
            inrAmount = null, sourceRef = WealthFixtures.ref(type, units, amount))

    @Test
    fun `units must move the way the type says`() {
        row(InvestmentTxnType.BUY, "10.123")
        row(InvestmentTxnType.SELL, "-4")
        row(InvestmentTxnType.DIVIDEND_PAYOUT, null)
        row(InvestmentTxnType.REVERSAL, "-10.123")
        row(InvestmentTxnType.SPLIT, "10")
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.BUY, "-1") }
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.SIP_BUY, null) }
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.SELL, "1") }
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.STAMP_DUTY, "1") }
    }

    @Test
    fun `amounts are never negative and have at most 2 places`() {
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.BUY, "1", "-100") }
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.BUY, "1", "100.001") }
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.BUY, "1", "12345678901234567") }
    }

    @Test
    fun `an INR row has no FX rate and a foreign row converts to INR`() {
        val usd = Currency.getInstance("USD")
        val fx = FxRate(usd, INR, BigDecimal("83.25"), FxRateType.SBI_TTBR, day)
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.BUY, "1", fx = fx) }
        val eur = FxRate(Currency.getInstance("EUR"), INR, BigDecimal("90"), FxRateType.ECB, day)
        assertFailsWith<IllegalArgumentException> { row(InvestmentTxnType.BUY, "1", currency = usd, fx = eur) }
        val foreign = row(InvestmentTxnType.BUY, "1", currency = usd, fx = fx).copy(inrAmount = BigDecimal("8325.00"))
        assertEquals(BigDecimal("8325.00"), foreign.amountInr)
        assertEquals(BigDecimal("100.00"), row(InvestmentTxnType.BUY, "1").amountInr)
    }

    @Test
    fun `a source ref is a 64 character hex digest`() {
        assertFailsWith<IllegalArgumentException> { buy(user, acct, fund, null, day, "1", "10", "10").copy(sourceRef = "folio-123") }
    }

    @Test
    fun `instruments need a valid identifier and accounts a sane owner share`() {
        assertFailsWith<IllegalArgumentException> { Instrument(name = "No id", assetClass = AssetClass.DEBT) }
        assertFailsWith<IllegalArgumentException> { Instrument(name = "Bad", assetClass = AssetClass.DEBT, isin = "inf123") }
        assertFailsWith<IllegalArgumentException> { Instrument(name = "Bad", assetClass = AssetClass.DEBT, amfiCode = "12a") }
        assertFailsWith<IllegalArgumentException> { Instrument(name = "Bad", assetClass = AssetClass.DEBT, ticker = "<script>") }
        assertFailsWith<IllegalArgumentException> { Instrument(name = " ", assetClass = AssetClass.DEBT, ticker = "ABC") }
        assertFailsWith<IllegalArgumentException> { acct.copy(ownerShare = BigDecimal("1.5")) }
        assertFailsWith<IllegalArgumentException> { acct.copy(ownerShare = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { acct.copy(institution = "") }
    }

    @Test
    fun `batch counts must add up and reader codes stay short`() {
        assertFailsWith<IllegalArgumentException> { WealthFixtures.batch(user, events = 1).copy(added = 2) }
        assertFailsWith<IllegalArgumentException> { WealthFixtures.batch(user).copy(readerId = "Robert'); DROP TABLE") }
    }

    @Test
    fun `a protected identifier shows only its last 4 and must be ciphertext`() {
        assertEquals("••••0001", acct.identifier.masked)
        assertFailsWith<IllegalArgumentException> { ProtectedIdentifier("90000001", "a".repeat(64), "0001") }
        assertFailsWith<IllegalArgumentException> { ProtectedIdentifier("v2:abc", "xyz", "0001") }
        assertNull(InvestmentTxnType.entries.firstOrNull { it.wire != it.name.lowercase() })
        assertEquals(InvestmentAccountKind.MF_FOLIO, InvestmentAccountKind.fromWire("mf_folio"))
        assertEquals(AssetClass.EQUITY_IN, AssetClass.fromWire("equity_in"))
    }
}
