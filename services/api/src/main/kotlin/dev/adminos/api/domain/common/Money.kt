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
