package dev.adminos.api.domain.ai

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * Money set aside for one call before it is made: its worst-case cost in [month] (UTC, as vendors bill).
 * Settled to the real cost afterwards, in the same month even if the call crosses midnight.
 */
data class AiReservation(val month: YearMonth, val micros: Long) {
    init {
        require(micros >= 0) { "A reservation is not negative" }
    }
}

/**
 * This server's AI spend per month (AOS-702). Reservations make the cap hold under concurrent calls and
 * several API instances: [reserve] is atomic against the cap (on Postgres it locks the month's row).
 */
interface AiSpendLedger {
    /** Adds [micros] to [month]'s reserved amount if spent + reserved + micros ≤ [capMicros]. False otherwise. */
    suspend fun reserve(month: YearMonth, micros: Long, capMicros: Long): Boolean

    /** Releases [reservedMicros] of [month]'s reservation and adds [actualMicros] to its spend. */
    suspend fun settle(month: YearMonth, reservedMicros: Long, actualMicros: Long)

    /** What [month] has spent so far (settled calls only). */
    suspend fun spent(month: YearMonth): Long
}

class InMemoryAiSpendLedger : AiSpendLedger {
    private data class Month(val spent: Long, val reserved: Long)
    private val months = mutableMapOf<YearMonth, Month>()
    private val lock = Any()

    override suspend fun reserve(month: YearMonth, micros: Long, capMicros: Long): Boolean = synchronized(lock) {
        val m = months[month] ?: Month(0, 0)
        if (m.spent + m.reserved + micros > capMicros) return@synchronized false
        months[month] = m.copy(reserved = m.reserved + micros)
        true
    }

    override suspend fun settle(month: YearMonth, reservedMicros: Long, actualMicros: Long): Unit = synchronized(lock) {
        val m = months[month] ?: Month(0, 0)
        months[month] = Month(spent = m.spent + actualMicros, reserved = maxOf(0, m.reserved - reservedMicros))
    }

    override suspend fun spent(month: YearMonth): Long = synchronized(lock) { months[month]?.spent ?: 0 }
}

/**
 * The monthly cap on AI spend (D-67), which **fails closed**: a call whose worst case would pass the cap is
 * not made, and a call whose real cost is unknown (the provider threw) is charged its worst case. A free
 * (local) call reserves 0, so it is never blocked by the cap.
 */
class AiCostGuard(
    private val ledger: AiSpendLedger,
    val capMicros: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(AiCostGuard::class.java)
    private val warnedFor = AtomicReference<YearMonth?>(null)

    init {
        require(capMicros >= 0) { "The monthly cap is not negative" }
    }

    fun currentMonth(): YearMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC))

    /** A reservation for [worstCaseMicros], or null when it would pass this month's cap. */
    suspend fun reserve(worstCaseMicros: Long): AiReservation? {
        require(worstCaseMicros >= 0) { "A cost is not negative" }
        val month = currentMonth()
        if (ledger.reserve(month, worstCaseMicros, capMicros)) return AiReservation(month, worstCaseMicros)
        // Warn once a month, not once per refused call.
        if (warnedFor.getAndSet(month) != month) {
            logger.warn("AI monthly cap of US$ {} reached for {}: AI calls are refused until next month or a higher AI_MONTHLY_CAP_USD",
                Micros.toUsd(capMicros), month)
        }
        return null
    }

    suspend fun settle(reservation: AiReservation, actualMicros: Long) {
        require(actualMicros >= 0) { "A cost is not negative" }
        ledger.settle(reservation.month, reservation.micros, actualMicros)
    }

    suspend fun spentThisMonth(): Long = ledger.spent(currentMonth())
}
