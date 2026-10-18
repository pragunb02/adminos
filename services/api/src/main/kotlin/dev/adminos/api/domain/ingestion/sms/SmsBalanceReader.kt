package dev.adminos.api.domain.ingestion.sms

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** A bank account balance an SMS shows (AOS-720). Only these fields survive; the text does not (D-33). */
data class SmsBalance(
    val amount: BigDecimal,
    /** Last 4 digits, or 3 when the bank masks more ("Acct XX123"). */
    val accountHint: String,
    /** "as on 15-10-2026": the day the bank says the balance was true, when it says one. */
    val asOn: LocalDate?
) {
    override fun toString() = "SmsBalance(asOn=$asOn)" // never the amount or the account
}

/** Why an SMS gave no balance (AOS-720). The eval set expects these; they never quote the text. */
enum class NoBalance {
    /** No balance phrase with a figure. */
    NONE,
    /** A credit card's available limit or outstanding: not money in an account. */
    CREDIT_CARD,
    /** A wallet, loan, deposit, reward or other non-account balance. */
    NOT_AN_ACCOUNT,
    /** An opening, closing (a period's summary), average or minimum balance: not today's figure. */
    NOT_CURRENT,
    /** Two different balance figures of the same kind: never guessed. */
    AMBIGUOUS_FIGURE,
    /** Two different accounts named: whose balance it is can't be told. */
    AMBIGUOUS_ACCOUNT,
    /** The balance names no account. */
    NO_ACCOUNT,
    /** The sender isn't a bank we know (a generic or unknown header), so its figures aren't trusted. */
    UNTRUSTED_SENDER,
    /** The balance phrase is inside the merchant's name, which the merchant chose. */
    IN_MERCHANT,
    /** The message is one we never read (OTP, failed, promise, request, notice, unclear). */
    NOT_READ
}

sealed interface SmsBalanceResult {
    data class Found(val balance: SmsBalance) : SmsBalanceResult
    data class NotFound(val reason: NoBalance) : SmsBalanceResult
}

/**
 * Reads the account balance a bank SMS quotes ("Avl Bal: INR 12,345.67", "Available balance in A/c XX1234 is
 * Rs 9,000 CR", "Clr Bal Rs 500.00 Dr"), deterministically (D-67, D-170). Indian banks quote the balance **after**
 * the transaction the SMS is about, so the reading is as of the SMS's time.
 *
 * Fail closed, never a guess:
 * - credit cards (available limit, outstanding), wallets, loans, deposits and reward points are not account balances;
 * - an available balance is preferred to a ledger/closing one; two different figures of the same kind are refused;
 * - two different accounts in one message are refused (an account number is preferred over a debit card's);
 * - an opening, closing, average or minimum balance is not a current balance;
 * - a balance phrase right after "at", "to", "from" or "VPA" is a name the payee chose ("at AVL BAL RS 99999 On"), not a figure.
 *
 * Which messages may be read at all (a known bank's sender, a transaction or a balance alert) is [SmsBalanceGate].
 */
object SmsBalanceReader {
    private val I = RegexOption.IGNORE_CASE

    /** "Avl Bal", "Avbl. Bal", "Aval Bal", "Available Balance", "Clr Bal", "Ledger Bal", "A/c Bal", plain "Bal". */
    private val LABEL = Regex(
        "\\b(?:(?<kind>avl|avbl|aval|avail|available|net\\s+avl|clr|clear|cleared|ledger|ldgr|closing|current|a/c|acct|account|total(?:\\s+avbl)?)\\.?\\s*)?" +
            "(?:bal|balance)\\b\\.?", I
    )

    /**
     * The figure after a label: "Rs 1,234.56", "INR -500", "₹ 9,000 CR", "Rs.500.00 Dr", or a bare "12,345.67" / "750.00"
     * (a bare one needs a thousands comma or 3 whole digits, so a time like "10.30" is never a balance).
     */
    private val FIGURE = Regex(
        "(?:(?:Rs\\.?|INR|₹)\\s*(?<minus>-)?(?<cur>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)|(?<bare>[0-9]{1,3}(?:,[0-9]{2,3})+\\.[0-9]{2}|[0-9]{3,}\\.[0-9]{2})(?![0-9.]))" +
            "(?:\\s*(?<mark>Cr|Dr)\\b\\.?)?", I
    )

    /** What may sit between a label and its figure: "is", "of", ":", "in A/c XX1234", "as on 15-10-26". */
    private val BETWEEN = Regex(
        "^[\\s:.\\-=]*(?:(?:is|of|was|in|for|your|(?:[a-z]{2,10}\\s+)?bank|a/c|ac|acct|account|no\\.?|ending|with|as\\s+(?:on|of)|on|at|[X*x]*\\d{3,6}|" +
            "\\d{1,2}[-/.](?:\\d{1,2}|[A-Za-z]{3})[-/.]\\d{2,4}|\\d{1,2}[:.]\\d{2}(?::\\d{2})?(?:\\s*[AP]M)?|hrs?)[\\s:.,\\-=]*){0,10}$", I
    )

    private val NON_ACCOUNT_BEFORE = Regex("\\b(?:wallet|reward|points?|loan|emi|fd|rd|deposit|ppf|outstanding|o/s|unbilled|due|min(?:imum)?|average|avg|mab|amb|opening|monthly|quarterly|limit|credit\\s+card|card)\\s*\\.?\\s*$", I)
    /** "at AVL BAL RS 99999 On …", "to Bal Traders": a payee's or merchant's name, which they chose. */
    private val PAYEE_BEFORE = Regex("\\b(?:at|to|from|vpa|towards|info[-:]?|merchant|payee)\\s*[:\\-]?\\s*$", I)
    private val OPENING_BEFORE = Regex("\\b(?:opening|min(?:imum)?|average|avg|mab|amb|monthly|quarterly)\\s*\\.?\\s*$", I)

    /** A credit card's limit or dues: the message is about a card account, not money in a bank account. */
    private val CREDIT_CARD = Regex("\\b(?:credit\\s+card|cc\\s+(?:no|ending|xx)|avl\\.?\\s*(?:credit\\s+)?(?:limit|lmt)|available\\s+(?:credit\\s+)?limit|credit\\s+limit|total\\s+(?:amt\\s+)?due|min(?:imum)?\\s+(?:amt\\s+|amount\\s+)?due|outstanding)\\b", I)
    private val NOT_AN_ACCOUNT = Regex("\\b(?:wallet|paytm\\s+balance|amazon\\s+pay\\s+balance|gift\\s+card|reward\\s+points?|loan\\s+(?:a/c|account)|fixed\\s+deposit|recurring\\s+deposit|ppf)\\b", I)

    /** Account numbers: "A/c XX1234", "Acct XX123", "a/c no. XXXX5678", "Account ending 4321", "A/c *1234". */
    private val ACCOUNT = Regex("\\b(?:A/?c|Acct|Account|Ac)(?:\\s+no\\.?|\\s+number)?(?:\\s+ending(?:\\s+with|\\s+in)?)?\\s*[:\\-]?\\s*[X*x]*\\s*(\\d{3,})\\b", I)
    /** Debit card numbers: "Debit Card XX9876", "Card ending 9876". */
    private val CARD = Regex("\\b(?:debit\\s+)?card(?:\\s+no\\.?)?(?:\\s+ending(?:\\s+with|\\s+in)?)?\\s*[:\\-]?\\s*[X*x]*\\s*(\\d{3,})\\b", I)

    private val AS_ON = Regex("\\bas\\s+(?:on|of)\\s+(\\d{1,2})[-/.]([A-Za-z]{3}|\\d{1,2})[-/.](\\d{2}|\\d{4})\\b", I)

    /** Which figure wins when a message quotes several: available, then cleared, then ledger/closing, then a plain "Bal". */
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private enum class Kind(val rank: Int) { AVAILABLE(0), CLEARED(1), LEDGER(2), PLAIN(3) }

    private data class Figure(val kind: Kind, val amount: BigDecimal, val start: Int)

    fun read(text: String): SmsBalanceResult {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val figures = mutableListOf<Figure>()
        var excluded: NoBalance? = null
        for (label in LABEL.findAll(flat)) {
            val before = flat.substring(maxOf(0, label.range.first - 24), label.range.first)
            val kindWord = label.groups["kind"]?.value?.lowercase()?.replace(Regex("\\s+"), " ")
            if (PAYEE_BEFORE.containsMatchIn(before)) {
                excluded = excluded ?: NoBalance.IN_MERCHANT
                continue
            }
            if (kindWord == "closing") { // a period's closing balance in an SMS is a summary, not today's figure
                excluded = excluded ?: NoBalance.NOT_CURRENT
                continue
            }
            if ((kindWord == null || kindWord == "total") && NON_ACCOUNT_BEFORE.containsMatchIn(before)) {
                excluded = excluded ?: if (OPENING_BEFORE.containsMatchIn(before)) NoBalance.NOT_CURRENT else NoBalance.NOT_AN_ACCOUNT
                continue
            }
            val figure = figureAfter(flat, label.range.last + 1) ?: continue
            figures += Figure(kindOf(kindWord), figure, label.range.first)
        }
        if (figures.isEmpty()) {
            return SmsBalanceResult.NotFound(when {
                NOT_AN_ACCOUNT.containsMatchIn(flat) -> NoBalance.NOT_AN_ACCOUNT
                CREDIT_CARD.containsMatchIn(flat) -> NoBalance.CREDIT_CARD
                else -> excluded ?: NoBalance.NONE
            })
        }
        if (NOT_AN_ACCOUNT.containsMatchIn(flat)) return SmsBalanceResult.NotFound(NoBalance.NOT_AN_ACCOUNT)
        if (CREDIT_CARD.containsMatchIn(flat)) return SmsBalanceResult.NotFound(NoBalance.CREDIT_CARD)

        val best = figures.minOf { it.kind.rank }
        val amounts = figures.filter { it.kind.rank == best }.map { it.amount }.toSet()
        val amount = amounts.singleOrNull() ?: return SmsBalanceResult.NotFound(NoBalance.AMBIGUOUS_FIGURE)

        val accounts = ACCOUNT.findAll(flat).map { hint(it.groupValues[1]) }.toSet()
        val hints = accounts.ifEmpty { CARD.findAll(flat).map { hint(it.groupValues[1]) }.toSet() }
        val account = when (hints.size) {
            0 -> return SmsBalanceResult.NotFound(NoBalance.NO_ACCOUNT)
            1 -> hints.single()
            else -> return SmsBalanceResult.NotFound(NoBalance.AMBIGUOUS_ACCOUNT)
        }
        return SmsBalanceResult.Found(SmsBalance(amount, account, asOn(flat)))
    }

    /** True when [merchant] (text the merchant chose) contains a balance phrase with a figure. */
    fun inMerchant(merchant: String?): Boolean =
        merchant != null && LABEL.findAll(merchant).any { figureAfter(merchant, it.range.last + 1) != null }

    /** The figure that follows a label at [from], with only connecting words between. */
    private fun figureAfter(text: String, from: Int): BigDecimal? {
        val window = text.substring(from, minOf(text.length, from + 60))
        val m = FIGURE.findAll(window).firstOrNull { BETWEEN.matches(window.substring(0, it.range.first)) } ?: return null
        val raw = (m.groups["cur"] ?: m.groups["bare"])?.value?.replace(",", "") ?: return null
        val value = raw.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP) ?: return null
        val negative = m.groups["minus"] != null || m.groups["mark"]?.value?.lowercase() == "dr"
        return if (negative) value.negate() else value
    }

    private fun kindOf(word: String?): Kind = when (word) {
        "avl", "avbl", "aval", "avail", "available", "net avl", "total avbl" -> Kind.AVAILABLE
        "clr", "clear", "cleared" -> Kind.CLEARED
        "ledger", "ldgr", "current" -> Kind.LEDGER
        else -> Kind.PLAIN
    }

    /** The last 4 digits of a longer number; a 3-digit masked suffix stays 3. */
    private fun hint(digits: String): String = if (digits.length >= 4) digits.takeLast(4) else digits

    private fun asOn(text: String): LocalDate? {
        val m = AS_ON.find(text) ?: return null
        val (d, mo, y) = m.destructured
        val year = if (y.length == 2) "20$y" else y
        val month = mo.toIntOrNull() ?: (MONTHS.indexOf(mo.lowercase()) + 1).takeIf { it > 0 } ?: return null
        return try {
            LocalDate.of(year.toInt(), month, d.toInt())
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}
