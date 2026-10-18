package dev.adminos.api.domain.wealth

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-802: exact quantity, price and FX types; nothing rounds silently and nothing is a Double. */
class NumbersTest {

    @Test
    fun `quantities are exact and normalised so equal amounts are equal`() {
        assertEquals(Quantity.parse("1.500"), Quantity.parse("1.5"))
        assertEquals("0.3", (Quantity.parse("0.1") + Quantity.parse("0.2")).toString())
        assertEquals("-12.3456", (-Quantity.parse("12.3456")).toString())
        assertEquals("100", Quantity.parse("100.000").toString())
        assertEquals(Quantity.ZERO, Quantity.parse("5") - Quantity.parse("5.0"))
        assertTrue((Quantity.parse("5") - Quantity.parse("5")).isZero)
        assertEquals("246.912", Quantity.parse("123.456").times(BigDecimal("2")).toString())
        assertEquals(Quantity.parse("3"), Quantity.parse("-3").abs())
        assertTrue(Quantity.parse("2") > Quantity.parse("1.999999"))
    }

    @Test
    fun `a quantity keeps crypto precision up to 18 places and refuses more`() {
        assertEquals("0.000000000000000001", Quantity.parse("0.000000000000000001").toString())
        assertFailsWith<IllegalArgumentException> { Quantity.of(BigDecimal("0.0000000000000000001")) }
        assertFailsWith<IllegalArgumentException> { Quantity.of(BigDecimal("1".repeat(21))) }
    }

    @Test
    fun `wire decimals refuse exponents, grouping, spaces and words`() {
        for (bad in listOf("1e5", "1,000.00", " 1", "1 ", "NaN", "Infinity", "", "+1", "1.", ".5", "0x10", "1".repeat(41), "1.2.3", "١٢٣")) {
            assertFailsWith<IllegalArgumentException>(bad) { Quantity.parse(bad) }
        }
    }

    @Test
    fun `a unit price is positive and kept at the source's precision`() {
        assertEquals("45.6789", UnitPrice.parse("45.67890").toString())
        assertEquals(BigDecimal("4567.8900"), UnitPrice.parse("45.6789").valueOf(Quantity.parse("100")).setScale(4))
        assertFailsWith<IllegalArgumentException> { UnitPrice.parse("0") }
        assertFailsWith<IllegalArgumentException> { UnitPrice.parse("0.0000") }
        assertFailsWith<IllegalArgumentException> { UnitPrice.parse("-1") }
        assertFailsWith<IllegalArgumentException> { UnitPrice.of(BigDecimal("1.00000000001")) }
        assertTrue(UnitPrice.parse("10") > UnitPrice.parse("9.9999"))
    }

    @Test
    fun `an FX rate converts exactly and refuses nonsense`() {
        val usd = Currency.getInstance("USD")
        val rate = FxRate(usd, INR, BigDecimal("83.2512"), FxRateType.FBIL, LocalDate.of(2026, 10, 16))
        assertEquals(BigDecimal("8325.120000"), rate.convert(BigDecimal("100.00")), "exact: no rounding in conversion")
        assertFailsWith<IllegalArgumentException> { FxRate(INR, INR, BigDecimal.ONE, FxRateType.ECB, LocalDate.of(2026, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { FxRate(usd, INR, BigDecimal.ZERO, FxRateType.ECB, LocalDate.of(2026, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { FxRate(usd, INR, BigDecimal("83.12345678901"), FxRateType.ECB, LocalDate.of(2026, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { FxRate(usd, INR, BigDecimal("12345678901"), FxRateType.ECB, LocalDate.of(2026, 1, 1)) }
    }

    @Test
    fun `currency amounts follow the currency's own decimal places`() {
        CurrencyAmount(BigDecimal("10.25"), INR)
        CurrencyAmount(BigDecimal("1500"), Currency.getInstance("JPY"))
        assertFailsWith<IllegalArgumentException> { CurrencyAmount(BigDecimal("10.255"), INR) }
        assertFailsWith<IllegalArgumentException> { CurrencyAmount(BigDecimal("1500.5"), Currency.getInstance("JPY")) }
    }
}
