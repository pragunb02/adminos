package dev.adminos.api.domain.wealth.returns

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Natural log and exponential on [BigDecimal] (AOS-812), so XIRR's fractional powers `(1 + r)^t` need no `Double`
 * (D-79). Series with argument reduction, at [MC] (50 significant digits): far beyond the 10 places a rate is kept to.
 */
internal object BigMath {
    val MC = MathContext(50, RoundingMode.HALF_EVEN)
    private val TWO = BigDecimal(2)
    private val HALF = BigDecimal("0.5")
    private val EPS = BigDecimal.ONE.movePointLeft(MC.precision + 2)

    /** ln 2, from the atanh series at z = 1/3. */
    private val LN2: BigDecimal by lazy { lnNearOne(TWO) }

    /** ln(x) for x > 0. */
    fun ln(x: BigDecimal): BigDecimal {
        require(x.signum() > 0) { "ln is defined for positive numbers only" }
        var m = x
        var k = 0
        while (m > TWO) { m = m.divide(TWO, MC); k++ }
        while (m < HALF) { m = m.multiply(TWO, MC); k-- }
        return lnNearOne(m).add(LN2.multiply(BigDecimal(k), MC), MC)
    }

    /** ln(m) = 2·atanh((m − 1)/(m + 1)), which converges fast for m in [0.5, 2]. */
    private fun lnNearOne(m: BigDecimal): BigDecimal {
        val z = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), MC)
        val z2 = z.multiply(z, MC)
        var term = z
        var sum = BigDecimal.ZERO
        var n = 1
        while (true) {
            val add = term.divide(BigDecimal(n), MC)
            sum = sum.add(add, MC)
            if (add.abs() < EPS) break
            term = term.multiply(z2, MC)
            n += 2
        }
        return sum.multiply(TWO, MC)
    }

    /** e^y: halve y until it is small, sum the Taylor series, then square back. */
    fun exp(y: BigDecimal): BigDecimal {
        var r = y
        var k = 0
        while (r.abs() > HALF) { r = r.divide(TWO, MC); k++ }
        var term = BigDecimal.ONE
        var sum = BigDecimal.ONE
        var n = 1
        while (true) {
            term = term.multiply(r, MC).divide(BigDecimal(n), MC)
            sum = sum.add(term, MC)
            if (term.abs() < EPS) break
            n++
        }
        repeat(k) { sum = sum.multiply(sum, MC) }
        return sum
    }

    /** base^power for base > 0. */
    fun pow(base: BigDecimal, power: BigDecimal): BigDecimal = exp(power.multiply(ln(base), MC))
}
