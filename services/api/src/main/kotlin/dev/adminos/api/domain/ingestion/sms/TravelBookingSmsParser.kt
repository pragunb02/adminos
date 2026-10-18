package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.trips.TravelMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** A train booking read from an IRCTC SMS (AOS-910): only these fields survive (D-33); the PNR is hashed when stored. */
data class TravelBookingNotice(
    val brand: String,
    val mode: TravelMode,
    val pnr: String,
    val journeyDate: LocalDate?,
    val amount: BigDecimal?,
    val cancelled: Boolean
) {
    init {
        require(pnr.matches(Regex("\\d{10}"))) { "an IRCTC PNR is 10 digits" }
        require(amount == null || (amount.signum() > 0 && amount <= TravelBookingSmsParser.MAX_FARE)) { "a fare is positive and plausible" }
    }
}

/**
 * IRCTC booking and cancellation SMS ("PNR:4512345678,TRN:12627,DOJ:24-10-26,…,Fare:845.00"). The journey
 * date is what makes a trip land on the right days (D-90). Anything else is not read.
 */
object TravelBookingSmsParser {
    private val I = RegexOption.IGNORE_CASE
    val MAX_FARE = BigDecimal("200000.00")
    private const val MAX_TEXT = 1000

    private val IRCTC = Regex("\\bIRCTC\\b|\\bTRN\\s*[:.]", I)
    private val PNR = Regex("\\bPNR\\s*(?:No\\.?)?\\s*[:\\-]?\\s*(\\d{10})\\b", I)
    private val DOJ = Regex("\\bDOJ\\s*[:\\-]?\\s*(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})\\b", I)
    private val FARE = Regex("\\bFare\\s*[:\\-]?\\s*(?:Rs\\.?|INR|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
    private val CANCELLED = Regex("\\bcancel(?:l?ed|lation)\\b", I)
    private val DATE_LAYOUTS = listOf("d-M-uu", "d-M-uuuu", "d/M/uu", "d/M/uuuu").map {
        DateTimeFormatterBuilder().appendPattern(it).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    }

    /** Null when it isn't an IRCTC booking or cancellation message. [receivedOn] bounds the journey date. */
    fun parse(text: String, receivedOn: LocalDate): TravelBookingNotice? {
        if (text.length > MAX_TEXT || !IRCTC.containsMatchIn(text)) return null
        val pnr = PNR.find(text)?.groupValues?.get(1) ?: return null
        val cancelled = CANCELLED.containsMatchIn(text)
        val journey = DOJ.find(text)?.groupValues?.get(1)?.let { date(it, receivedOn) }
        if (!cancelled && journey == null) return null
        val fare = FARE.find(text)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
            ?.takeIf { it.signum() > 0 && it <= MAX_FARE }
        return TravelBookingNotice("IRCTC", TravelMode.TRAIN, pnr, journey, if (cancelled) null else fare, cancelled)
    }

    /** Day first; a journey is at most a day before the message and at most 400 days after it. */
    private fun date(raw: String, receivedOn: LocalDate): LocalDate? =
        DATE_LAYOUTS.firstNotNullOfOrNull { f -> try { LocalDate.parse(raw, f) } catch (e: DateTimeParseException) { null } }
            ?.takeIf { !it.isBefore(receivedOn.minusDays(1)) && !it.isAfter(receivedOn.plusDays(400)) }
}
