package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.paylater.PayLaterProvider
import dev.adminos.api.domain.financial.paylater.PayLaterProviders
import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/** A pay-later bill read from a provider's message (AOS-909): only these fields survive (D-33). */
data class PayLaterBillNotice(val provider: String, val amount: BigDecimal, val minimumDue: BigDecimal?, val dueDate: LocalDate) {
    init {
        require(amount.signum() > 0 && amount <= PayLaterSmsParser.MAX_AMOUNT) { "a pay-later bill is a positive amount up to the cap" }
        require(minimumDue == null || (minimumDue.signum() > 0 && minimumDue <= amount)) { "the minimum due is positive and at most the bill" }
    }
}

/** What a provider's message is about. */
sealed interface PayLaterMessage {
    data class Bill(val notice: PayLaterBillNotice) : PayLaterMessage
    data class Purchase(val transaction: ParsedSmsTransaction) : PayLaterMessage
    /** A provider's "payment received": the bank's own alert records the money, so this is nothing. */
    data object Repayment : PayLaterMessage
    /** About the provider, but not something we read (an offer, a limit change, a bill without a due date). */
    data class Unreadable(val reason: String) : PayLaterMessage
}

/**
 * Reads pay-later providers' messages (AOS-909): LazyPay, Simpl, Amazon Pay Later and the rest in
 * [PayLaterProviders]. It only speaks for messages that name a provider and are not a bank's account
 * alert ("debited from A/c XX1234 to LAZYPAY" is the bank's repayment record, read by the bank parser).
 * Repayment confirmations come first (they say "bill"), then bills, then purchases.
 */
object PayLaterSmsParser {
    private val I = RegexOption.IGNORE_CASE
    /** No pay-later bill or purchase is above ₹10 lakh; anything larger is a mis-read or hostile text. */
    val MAX_AMOUNT = BigDecimal("1000000.00")
    /** Texts longer than an SMS chain are not provider notices. */
    private const val MAX_TEXT = 2000

    /** A bank's account alert: an account with masked digits ("A/c XX1234", "account ending 1234"). Providers don't print those. */
    private val BANK_ALERT = Regex("\\b(?:a/c|acct|account|card)\\b\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:with\\s*)?)?[X*x]*\\s*\\d{3,4}\\b", I)
    /** A mandate notice about a provider (set up, paused…) is the mandate parser's, unless it is also a bill. */
    private val MANDATE = Regex("\\b(mandate|e-?nach|standing instruction|umn|umrn)\\b", I)
    private val REPAYMENT = Regex("\\b(?:re)?payment\\b.{0,50}\\b(?:received|successful|is successful|credited|processed)\\b|\\bthanks?(?: you)? for (?:the |your )?(?:re)?payment\\b|\\bbill (?:is |has been )?paid\\b", I)
    private val BILL = Regex("\\b(bill|statement|total due|amount due|due amount|outstanding|due date|due on|due by|pay by|repay by)\\b", I)
    private val PURCHASE = Regex("\\b(spent|paid|purchase|txn|transaction)\\b", I)
    private val MONEY = Regex("(?:Rs\\.?|INR|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
    private val MIN_DUE = Regex("\\bmin(?:imum)?\\.?\\s*(?:amount\\s*)?due\\b[^0-9₹]{0,15}(?:Rs\\.?|INR|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
    private val DUE_DATE = Regex("\\b(?:due(?:\\s+date)?|pay(?:able)?\\s+by|repay\\s+by|due\\s+on|due\\s+by|before|by)\\b[^0-9A-Za-z]{0,6}(?:on\\s+|by\\s+)?" +
        "(\\d{1,2}(?:st|nd|rd|th)?[-/ ](?:[A-Za-z]{3,9}|\\d{1,2})(?:[-/, ]+\\d{2,4})?|[A-Za-z]{3,9} \\d{1,2}(?:st|nd|rd|th)?(?:,? \\d{4})?)", I)
    private val MERCHANT = Regex("\\b(?:at|to|on|for)\\s+([A-Za-z0-9][A-Za-z0-9 &.'*-]{1,40}?)\\s+(?:using|via|with|through|on|is|was|has)\\b", I)
    private val DATE_LAYOUTS = listOf("d-MMM-uuuu", "d MMM uuuu", "d-MMMM-uuuu", "d MMMM uuuu", "d/M/uuuu", "d-M-uuuu", "d/M/uu", "d-M-uu",
        "d-MMM-uu", "d MMM uu", "MMM d uuuu", "MMMM d uuuu", "d MMM, uuuu", "MMM d, uuuu").map(::formatter)
    private val MONTH_DAY_LAYOUTS = listOf("d MMM", "d-MMM", "d MMMM", "d-MMMM", "MMM d", "MMMM d", "d/M", "d-M").map(::formatter)

    /** Day-first patterns, any letter case ("15-OCT-2026"), strict (no 31 February). */
    private fun formatter(pattern: String): DateTimeFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
        .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)

    /** Null when the text names no provider, or is a bank's own account alert. [receivedOn] dates a due date without a year. */
    fun parse(text: String, receivedOn: LocalDate): PayLaterMessage? {
        if (text.length > MAX_TEXT) return null
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val provider = PayLaterProviders.namedIn(flat) ?: return null
        if (BANK_ALERT.containsMatchIn(flat)) return null
        if (MANDATE.containsMatchIn(flat) && !BILL.containsMatchIn(flat)) return null
        return when {
            REPAYMENT.containsMatchIn(flat) -> PayLaterMessage.Repayment
            // "₹450 spent at Swiggy using LazyPay. Total due ₹1,684": a purchase that also states the balance.
            PURCHASE.containsMatchIn(flat) && merchantOf(flat) != null -> purchase(flat, provider)
            BILL.containsMatchIn(flat) -> bill(flat, provider, receivedOn)
            PURCHASE.containsMatchIn(flat) -> purchase(flat, provider)
            else -> PayLaterMessage.Unreadable("pay-later message without a bill or purchase")
        }
    }

    private fun bill(s: String, provider: PayLaterProvider, receivedOn: LocalDate): PayLaterMessage {
        val minimumMatch = MIN_DUE.find(s)
        val minimum = minimumMatch?.let { money(it.groupValues[1]) }
        val amount = MONEY.findAll(s).filterNot { m -> minimumMatch?.groups?.get(1)?.range == m.groups[1]?.range }
            .firstNotNullOfOrNull { money(it.groupValues[1]) }
            ?: return PayLaterMessage.Unreadable("pay-later bill without an amount")
        val due = DUE_DATE.find(s)?.groupValues?.get(1)?.let { dueDate(it, receivedOn) }
            ?: return PayLaterMessage.Unreadable("pay-later bill without a readable due date")
        if (amount > MAX_AMOUNT) return PayLaterMessage.Unreadable("pay-later amount out of range")
        return PayLaterMessage.Bill(PayLaterBillNotice(provider.name, amount, minimum?.takeIf { it <= amount }, due))
    }

    private fun purchase(s: String, provider: PayLaterProvider): PayLaterMessage {
        val amount = MONEY.find(s)?.let { money(it.groupValues[1]) }?.takeIf { it <= MAX_AMOUNT }
            ?: return PayLaterMessage.Unreadable("pay-later purchase without an amount")
        val merchant = merchantOf(s)
        return PayLaterMessage.Purchase(ParsedSmsTransaction(
            direction = IngestDirection.DEBIT, amount = amount, accountHint = null, merchantRaw = merchant?.take(255),
            upiVpa = null, paymentMethod = PaymentMethod.PAY_LATER, bankCode = null, markers = mapOf(PAY_LATER_MARKER to provider.name)
        ))
    }

    /** The merchant a purchase names ("at Swiggy using LazyPay"); never the provider itself, never a date. */
    private fun merchantOf(s: String): String? = MERCHANT.findAll(s).map { it.groupValues[1].trim() }
        .firstOrNull { m -> PayLaterProviders.namedIn(m) == null && !m.first().isDigit() }

    private fun money(raw: String): BigDecimal? =
        raw.replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.setScale(2, RoundingMode.HALF_UP)

    /**
     * A due date as Indian messages write it: day first. A date without a year is the first such date
     * from a week before the message on (a "due 5 Jan" text in late December is next year's).
     * Dates more than a year away are mis-reads.
     */
    internal fun dueDate(raw: String, receivedOn: LocalDate): LocalDate? {
        val clean = raw.replace(Regex("(?<=\\d)(st|nd|rd|th)\\b", I), "").replace(Regex("\\s*,\\s*"), " ").replace(Regex("\\s+"), " ").trim()
        val parsed = DATE_LAYOUTS.firstNotNullOfOrNull { f -> parseOrNull { LocalDate.parse(clean, f) } }
            ?: MONTH_DAY_LAYOUTS.firstNotNullOfOrNull { f -> parseOrNull { MonthDay.parse(clean, f) } }?.let { md ->
                val from = receivedOn.minusDays(7)
                generateSequence(from.year) { it + 1 }.take(2).map { y -> md.atYear(y) }.first { !it.isBefore(from) }
            }
        return parsed?.takeIf { !it.isBefore(receivedOn.minusYears(1)) && !it.isAfter(receivedOn.plusYears(1)) }
    }

    private fun <T> parseOrNull(block: () -> T): T? = try { block() } catch (e: DateTimeParseException) { null }

    /** The event metadata key that tags a purchase with its provider; it travels to the transaction. */
    const val PAY_LATER_MARKER = "payLater"
}
