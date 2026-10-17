package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month

/**
 * What a bank message itself says, read by plain code (AOS-705, D-67, D-106). The model's answer is checked
 * against these facts, never trusted on its own:
 * - [amounts]: every money mention (`Rs`, `Rs.`, `INR`, `₹` before or after the number, Indian commas, `.00`),
 *   and whether it is a balance or limit figure rather than the amount moved;
 * - [dates]: every date written in the text (day first, as Indian banks write them);
 * - [lastFours]: the 4 digits shown for an account or card (`A/c XX1234`, `card ending 1234`, `*1234`);
 * - [direction]: D-43's word families, plus the abbreviations Indian alerts use (`Dr`, `Cr`, `debit of`).
 * Every function is pure and reads the **redacted** text, which is exactly what the model saw.
 */
object MessageGrounding {

    /** One money figure in the text. [isBalance] marks "Avl Bal", "Limit", "Outstanding", "Total due" figures. */
    data class MoneyMention(val amount: BigDecimal, val isBalance: Boolean)

    private val I = RegexOption.IGNORE_CASE
    private const val NUMBER = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    private val MONEY_BEFORE = Regex("(?<![A-Za-z])(?:Rs\\.?|INR|₹)\\s*$NUMBER(?![0-9])", I)
    private val MONEY_AFTER = Regex("(?<![0-9.,])$NUMBER\\s*(?:INR|Rs\\b|rupees\\b)", I)

    /**
     * The words the regex parsers skip as "not the amount" (`BankSmsParser`), looked for just before a figure,
     * as whole words (so "GLOBAL STORES" is not a balance).
     */
    private val BALANCE_WORDS = Regex("\\b(avl|avail[a-z]*|bal|balance|limit|lmt|outstanding|due)\\b", I)
    private const val BALANCE_WINDOW = 18

    /** A money word: the fallback is only asked about messages that talk about money (research §6). */
    private val MONEY_WORD = Regex("\\b(debited|credited|spent|sent|paid|received|charged|withdrawn|INR|Rs)\\b|₹", I)

    fun mentionsMoney(text: String): Boolean = MONEY_WORD.containsMatchIn(text)

    fun amounts(text: String): List<MoneyMention> {
        val found = (MONEY_BEFORE.findAll(text) + MONEY_AFTER.findAll(text)).mapNotNull { m ->
            val amount = m.groupValues[1].replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            val before = text.substring(maxOf(0, m.range.first - BALANCE_WINDOW), m.range.first)
            m.range.first to MoneyMention(amount.stripTrailingZeros(), BALANCE_WORDS.containsMatchIn(before))
        }
        return found.sortedBy { it.first }.map { it.second }.toList()
    }

    // ── Dates ──

    private val MONTHS: Map<String, Month> = Month.entries.associateBy { it.name.take(3).lowercase() } + ("sept" to Month.SEPTEMBER)
    private const val MON = "(jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec)[a-z]*"
    private val NUMERIC_DMY = Regex("(?<![0-9])([0-9]{1,2})[-/.]([0-9]{1,2})[-/.]([0-9]{4}|[0-9]{2})(?![0-9])")
    private val ISO = Regex("(?<![0-9])([0-9]{4})-([0-9]{2})-([0-9]{2})(?![0-9])")
    private val DAY_MON_YEAR = Regex("(?<![0-9])([0-9]{1,2})(?:st|nd|rd|th)?[-/ ]?$MON[-/ ,']*([0-9]{4}|[0-9]{2})(?![0-9])", I)
    private val MON_DAY_YEAR = Regex("(?<![A-Za-z])$MON\\.? ([0-9]{1,2}),? ([0-9]{4})(?![0-9])", I)

    /** Every date written in [text]. Two-digit years are 20xx. Impossible dates (31/02) are not dates. */
    fun dates(text: String): Set<LocalDate> {
        val out = mutableSetOf<LocalDate>()
        NUMERIC_DMY.findAll(text).forEach { m -> date(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let(out::add) }
        ISO.findAll(text).forEach { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let(out::add) }
        DAY_MON_YEAR.findAll(text).forEach { m -> month(m.groupValues[2])?.let { mo -> date(year(m.groupValues[3]), mo.value, m.groupValues[1].toInt())?.let(out::add) } }
        MON_DAY_YEAR.findAll(text).forEach { m -> month(m.groupValues[1])?.let { mo -> date(m.groupValues[3].toInt(), mo.value, m.groupValues[2].toInt())?.let(out::add) } }
        return out
    }

    private fun year(raw: String): Int = if (raw.length == 2) 2000 + raw.toInt() else raw.toInt()

    private fun month(raw: String): Month? = MONTHS[raw.lowercase().take(4)] ?: MONTHS[raw.lowercase().take(3)]

    private fun date(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (e: DateTimeException) {
        null
    }

    // ── Account / card last 4 ──

    /**
     * The 4 digits that end a masked or labelled account or card number: `XX1234`, `*1234`, `A/c 1234`,
     * `card ending 1234`, `Acct no. XXXX1234`. A bare 4-digit number (a year, an amount) is not one.
     */
    private val LAST_FOUR = Regex(
        "(?:(?<![A-Za-z0-9])[Xx*]{1,12}[ -]?|\\b(?:a/?c|acct|account|card)\\b(?: no\\.?| number)?(?: ending(?: in| with)?)?[ :.#-]*[Xx*]*[ -]?)([0-9]{4})(?![0-9])", I
    )

    fun lastFours(text: String): Set<String> = LAST_FOUR.findAll(text).map { it.groupValues[1] }.toSet()

    // ── Direction (D-43, extended for SMS abbreviations) ──

    /** What happened to *your* account wins (D-43): "debited from your a/c", "credited to card". */
    private val ANCHORED = Regex("\\b(debited|credited)\\s+(?:from|to|in(?:to)?)\\s+(?:your\\s+)?(?:a/c|ac|acct|account|card|credit card|bank account)\\b", I)
    private val DEBIT = Regex("\\b(debited|spent|paid|charged|purchase|withdrawn|sent|withdrawal|dr|debit(?!\\s*card))\\b", I)
    private val CREDIT = Regex("\\b(credited|received|refund(?:ed)?|deposited|cashback|reversed|cr|credit(?!\\s*(?:card|limit)))\\b", I)

    /** The direction the words say, or null when they say neither or both (never guessed, D-43). */
    fun direction(text: String): IngestDirection? {
        ANCHORED.find(text)?.let { return if (it.groupValues[1].equals("credited", ignoreCase = true)) IngestDirection.CREDIT else IngestDirection.DEBIT }
        val debit = DEBIT.containsMatchIn(text)
        val credit = CREDIT.containsMatchIn(text)
        return when {
            debit && !credit -> IngestDirection.DEBIT
            credit && !debit -> IngestDirection.CREDIT
            else -> null
        }
    }
}
