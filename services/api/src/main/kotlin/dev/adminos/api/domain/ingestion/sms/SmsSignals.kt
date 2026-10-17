package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.ingestion.events.IngestDirection

/** What a message says happened to the user's money (D-43 applied to SMS, AOS-215). */
sealed interface SmsDirection {
    data class Clear(val direction: IngestDirection) : SmsDirection
    /** Signals disagree: never guessed, the message is reported as needing review. */
    data object Conflicting : SmsDirection
    /** No direction at all: not a transaction message. */
    data object None : SmsDirection
}

/**
 * The words that decide whether, and which way, money moved. Shared by the drop rules ([SmsParserRegistry]) and
 * the parsers ([BankSmsParser]), so "is this a transaction?" and "which way?" can never disagree.
 *
 * Direction (AOS-215, following D-43):
 * 1. **Strong signals:** what happened to an account ("debited from A/c", "A/c XX1234 credited", "Dr. from A/C")
 *    and explicit Dr/Cr markers on an amount ("Dr. Rs 500", "INR 500.00 Cr"). If they agree, that's the direction.
 * 2. Otherwise **verbs** (debited, spent, paid, withdrawn, debit… / credited, received, refund, credit…), if they
 *    all point one way.
 * 3. Disagreement at the deciding level is [SmsDirection.Conflicting]; nothing is guessed.
 *
 * Balance and limit figures ("Avl Bal Rs 4,500.00 Cr") are removed before any of this, so a balance is
 * never read as a transaction or as its direction.
 */
object SmsSignals {
    private val I = RegexOption.IGNORE_CASE
    private const val MONEY = "(?:Rs\\.?|INR|₹)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?"
    private const val ACCOUNT = "(?:a/c|ac|acct|account|card)"

    /** "Avl Bal: Rs 4,500.00 Cr", "Available balance in A/c XX1234 is INR 9,000", "Avl Limit: INR 2,00,000". */
    private val BALANCE_FIGURE = Regex(
        "\\b(?:(?:avl|avail(?:able)?|clear|ledger|closing|total)\\.?\\s*)?(?:bal(?:ance)?|lmt|limit)\\b[^!;\\n]{0,60}?$MONEY(?:\\s*(?:Cr|Dr)\\b\\.?)?", I
    )

    /** "debited from your HDFC Bank A/c", "Dr. from A/C", "credited to A/c"; never "credited to VPA x". */
    private val ANCHORED_VERB_FIRST = Regex("\\b(debited|credited|Dr|Cr)\\.?\\s+(?:from|to|in(?:to)?)\\s+(?:your\\s+)?(?:[A-Za-z]+\\s+){0,3}?$ACCOUNT\\b", I)

    /** "A/c XX1234 debited", "Acct XX234 is credited", "a/c no. XXXX5678 has been Dr.". */
    private val ANCHORED_ACCOUNT_FIRST = Regex(
        "\\b$ACCOUNT(?:\\s+no\\.?)?(?:\\s+ending(?:\\s+with)?)?\\s*[X*x]*\\d{3,6}\\s+(?:is\\s+|has\\s+been\\s+|was\\s+|got\\s+)?(debited|credited|Dr|Cr)\\b", I
    )

    /** An explicit Dr/Cr on an amount: "Dr. Rs 500", "Cr. with INR 3,000", "Rs.500.00 Dr". */
    private val MARKER_BEFORE_MONEY = Regex("\\b(Dr|Cr)\\b\\.?\\s*(?:(?:with|by|for|of)\\s+)?$MONEY", I)
    private val MARKER_AFTER_MONEY = Regex("$MONEY\\s*(Dr|Cr)\\b", I)

    /** "Debit card", "credit card", "credit limit" name a product, not a movement of money. */
    private val DEBIT_WORDS = Regex("\\b(?:debited|spent|sent|paid|withdrawn|purchase|deducted|transferred to|debit(?!\\s+card))\\b", I)
    private val CREDIT_WORDS = Regex("\\b(?:credited|received|deposited|refund(?:ed)?|reversed|credit(?!\\s+(?:card|limit|score)))\\b", I)

    /** Says a transaction happened without saying which way: "UPI txn of Rs 500". */
    private val TRANSACTION_WORDS = Regex("\\b(?:txn|transaction)\\b", I)

    /** [text] with balance and limit figures blanked out. */
    fun withoutBalances(text: String): String = BALANCE_FIGURE.replace(text, " ")

    fun direction(text: String): SmsDirection {
        val s = withoutBalances(text)
        val strong = buildSet {
            (ANCHORED_VERB_FIRST.findAll(s) + ANCHORED_ACCOUNT_FIRST.findAll(s) + MARKER_BEFORE_MONEY.findAll(s) + MARKER_AFTER_MONEY.findAll(s))
                .forEach { add(directionOf(it.groupValues[1])) }
        }
        decide(strong)?.let { return it }
        val weak = buildSet {
            if (DEBIT_WORDS.containsMatchIn(s)) add(IngestDirection.DEBIT)
            if (CREDIT_WORDS.containsMatchIn(s)) add(IngestDirection.CREDIT)
        }
        return decide(weak) ?: SmsDirection.None
    }

    /** The text, balances aside, says some money moved (or that a transaction happened). */
    fun mentionsTransaction(text: String): Boolean =
        direction(text) != SmsDirection.None || TRANSACTION_WORDS.containsMatchIn(withoutBalances(text))

    private fun decide(found: Set<IngestDirection>): SmsDirection? = when (found.size) {
        0 -> null
        1 -> SmsDirection.Clear(found.single())
        else -> SmsDirection.Conflicting
    }

    private fun directionOf(word: String): IngestDirection =
        if (word.startsWith("d", ignoreCase = true)) IngestDirection.DEBIT else IngestDirection.CREDIT
}

/**
 * Credits that are promised, not made (AOS-213): "Rs 50 cashback will be credited…", "refund … shall be credited",
 * "to be credited", "credited within 3 days", "eligible for cashback". Like an upcoming debit, a promise is not a
 * transaction. Only the clause that promises is removed, so a real debit or credit in the same SMS
 * ("Rs 2,000 spent at X. Cashback of Rs 100 will be credited") is still read, without the promised amount.
 */
object PromisedCredits {
    private val I = RegexOption.IGNORE_CASE

    private val PROMISE = Regex(
        listOf(
            "\\b(?:will|shall|would|should)\\s+(?:soon\\s+|shortly\\s+|now\\s+)?(?:be|get)\\s+(?:credited|refunded|reversed|deposited|added)\\b",
            "\\b(?:yet\\s+)?to\\s+be\\s+(?:credited|refunded|reversed)\\b",
            "\\bcredited\\s+(?:with)?in\\s+(?:the\\s+next\\s+)?\\d+(?:\\s*(?:-|to)\\s*\\d+)?\\s*(?:working\\s+|business\\s+)?(?:days?|hours?|hrs?)\\b",
            "\\beligible\\s+for\\s+(?:an?\\s+)?(?:instant\\s+)?(?:cashback|refund|reward)s?\\b",
            // "Refund of Rs 499 has been initiated": started, not received
            "\\brefund\\b[^;!\\n]{0,60}?\\b(?:initiated|is being processed|under process)\\b"
        ).joinToString("|"), I
    )

    /**
     * Clause ends: `;`, `!`, a new line, " and ", or a full stop before a capital letter that doesn't end an
     * abbreviation ("Rs.", "A/c no.", "Dr.").
     */
    private val CLAUSE_END = Regex("[;!\\n]|\\s+and\\s+|(?<!\\b(?i:no|rs|dr|cr|bal|avl|ref|a/c|ltd|pvt|mr|ms|mrs|st))\\.(?=\\s+[A-Z]|\\s*$)")

    /** @return [text] without its promising clauses, or null when nothing in it is a promise */
    fun strip(text: String): String? {
        if (!PROMISE.containsMatchIn(text)) return null
        val kept = StringBuilder()
        var start = 0
        fun take(end: Int, separator: String) {
            val clause = text.substring(start, end)
            if (!PROMISE.containsMatchIn(clause)) kept.append(clause)
            kept.append(separator) // the rest of the text is kept exactly as written
        }
        for (m in CLAUSE_END.findAll(text)) {
            take(m.range.first, m.value)
            start = m.range.last + 1
        }
        take(text.length, "")
        return kept.toString()
    }
}
