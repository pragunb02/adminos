package dev.adminos.api.domain.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money on the wire (D-30, AOS-611): an exact decimal string with 2 places, e.g. "300.30".
 * Never a JSON number — a client that adds Doubles gets 300.29999999999995.
 */
fun BigDecimal.toMoney(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

/** Exact sum of amounts. */
fun Iterable<BigDecimal>.sumMoney(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

/**
 * Rupees as people in India read them (AOS-506): Indian digit grouping (₹12,34,567), paise only
 * when there are any, a real minus sign for negatives. Same rules as the web's `formatInr`.
 */
fun BigDecimal.formatInr(): String {
    val v = setScale(2, RoundingMode.HALF_UP)
    val negative = v.signum() < 0
    val plain = v.abs().toPlainString()
    val rupees = plain.substringBefore('.')
    val paise = plain.substringAfter('.')
    val grouped = if (rupees.length <= 3) rupees else {
        val head = rupees.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + rupees.takeLast(3)
    }
    return (if (negative) "−" else "") + "₹" + grouped + (if (paise == "00") "" else ".$paise")
}
