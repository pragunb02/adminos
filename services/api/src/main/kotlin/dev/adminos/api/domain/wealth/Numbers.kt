package dev.adminos.api.domain.wealth

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Exact numbers for wealth (AOS-802, D-79). Money stays `BigDecimal` (D-30); quantities, unit prices and FX rates
 * get their own types so a NAV can't be passed where a unit count is expected, and nothing is ever a `Double`.
 *
 * Each type checks its scale against its database column, so a value is never rounded silently on the way in:
 * a source with more decimals than we can store is refused, not truncated.
 */

/** How many units of an instrument (MF units 3–4 places, shares 0–6, crypto up to 18). Signed: a change can be negative. */
@JvmInline
value class Quantity private constructor(val value: BigDecimal) : Comparable<Quantity> {

    operator fun plus(other: Quantity) = of(value + other.value)
    operator fun minus(other: Quantity) = of(value - other.value)
    operator fun unaryMinus() = of(value.negate())
    /** Splits and bonuses: quantity × ratio, kept exact. */
    fun times(ratio: BigDecimal) = of(value.multiply(ratio))
    val signum: Int get() = value.signum()
    val isZero: Boolean get() = value.signum() == 0
    fun abs() = of(value.abs())
    override fun compareTo(other: Quantity) = value.compareTo(other.value)
    override fun toString(): String = value.toPlainString()

    companion object {
        /** Column `NUMERIC(38,18)`. */
        const val MAX_SCALE = 18
        const val MAX_INTEGER_DIGITS = 20
        val ZERO = Quantity(BigDecimal.ZERO)

        /** Normalised (trailing zeros dropped) so 1.500 and 1.5 are the same quantity. */
        fun of(value: BigDecimal): Quantity {
            val v = value.stripTrailingZeros().let { if (it.signum() == 0) BigDecimal.ZERO else if (it.scale() < 0) it.setScale(0) else it }
            require(v.scale() <= MAX_SCALE) { "quantity has more than $MAX_SCALE decimal places" }
            require(v.precision() - v.scale() <= MAX_INTEGER_DIGITS) { "quantity is too large" }
            return Quantity(v)
        }

        /** Parses a decimal string (D-31 wire format); throws [IllegalArgumentException] on anything else. */
        fun parse(text: String): Quantity = of(decimal(text, "quantity"))
    }
}

/** The price of one unit (a NAV, a share price), in the instrument's currency. Always positive: a price is never zero (D-79). */
@JvmInline
value class UnitPrice private constructor(val value: BigDecimal) : Comparable<UnitPrice> {
    /** Value of [quantity] units at this price, exact (round only for display). */
    fun valueOf(quantity: Quantity): BigDecimal = value.multiply(quantity.value)
    override fun compareTo(other: UnitPrice) = value.compareTo(other.value)
    override fun toString(): String = value.toPlainString()

    companion object {
        /** Column `NUMERIC(30,10)`. */
        const val MAX_SCALE = 10
        const val MAX_INTEGER_DIGITS = 20

        fun of(value: BigDecimal): UnitPrice {
            val v = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
            require(v.signum() > 0) { "a unit price must be positive" }
            require(v.scale() <= MAX_SCALE) { "unit price has more than $MAX_SCALE decimal places" }
            require(v.precision() - v.scale() <= MAX_INTEGER_DIGITS) { "unit price is too large" }
            return UnitPrice(v)
        }

        fun parse(text: String): UnitPrice = of(decimal(text, "unit price"))
    }
}

/** Which published rate an FX conversion used (D-79): the tax report states it, because they differ. */
enum class FxRateType { FBIL, SBI_TTBR, ECB, OTHER }

/**
 * 1 [base] = [rate] [quote], as published by [type] for [date]. Kept at the source's precision.
 * Never zero; base and quote differ.
 */
data class FxRate(val base: Currency, val quote: Currency, val rate: BigDecimal, val type: FxRateType, val date: LocalDate) {
    init {
        require(base != quote) { "an FX rate converts between two different currencies" }
        require(rate.signum() > 0) { "an FX rate must be positive" }
        require(rate.stripTrailingZeros().scale() <= MAX_SCALE) { "FX rate has more than $MAX_SCALE decimal places" }
        require(rate.precision() - rate.scale() <= MAX_INTEGER_DIGITS) { "FX rate is too large" }
    }

    /** [amount] in [base] converted to [quote], exact (callers round at display or at the statement's own rounding). */
    fun convert(amount: BigDecimal): BigDecimal = amount.multiply(rate)

    companion object {
        /** Column `NUMERIC(20,10)`. */
        const val MAX_SCALE = 10
        const val MAX_INTEGER_DIGITS = 10
    }
}

/** Money with its currency, for amounts that may not be rupees. Scale follows the currency (₹ and $ 2, ¥ 0). */
data class CurrencyAmount(val amount: BigDecimal, val currency: Currency) {
    init {
        require(amount.stripTrailingZeros().scale() <= currency.defaultFractionDigits.coerceAtLeast(0)) {
            "${currency.currencyCode} amounts have at most ${currency.defaultFractionDigits} decimal places"
        }
    }
}

val INR: Currency = Currency.getInstance("INR")

/** Exact decimal from a wire string: digits, an optional sign and fraction; no exponent, no grouping, no spaces. */
internal fun decimal(text: String, what: String): BigDecimal {
    require(text.length <= 64 && DECIMAL.matches(text)) { "$what must be a decimal string like 12.3456" }
    return BigDecimal(text)
}

private val DECIMAL = Regex("-?\\d{1,40}(\\.\\d{1,18})?")
