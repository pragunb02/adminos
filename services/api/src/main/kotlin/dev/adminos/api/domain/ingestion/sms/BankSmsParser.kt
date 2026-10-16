package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal

/**
 * Data-driven SMS parser: one instance per bank, configured with its headers and merchant patterns.
 * Shared extraction rules live here once:
 * - **direction** from verbs (debited/spent/sent… vs credited/received…);
 * - **amount** is the money value next to the verb, never "Avl Bal"/"Limit" figures;
 * - **account** last digits (ICICI masks 3 digits; only a full 4-digit hint is kept);
 * - **UPI VPA** and **payment method**.
 */
class BankSmsParser(
    override val bankCode: String?,
    override val headers: Set<String>,
    /** First group = merchant/counterparty. Tried in order; the bank's own first, then common ones. */
    private val merchantPatterns: List<Regex>
) : SmsParser {

    override fun parse(text: String): ParsedSmsTransaction? {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val direction = direction(flat) ?: return null
        val amount = amount(flat) ?: return null
        val vpa = VPA.find(flat)?.value?.lowercase()
        return ParsedSmsTransaction(
            direction = direction,
            amount = amount,
            accountHint = ACCOUNT.find(flat)?.groupValues?.get(1)?.takeIf { it.length == 4 },
            merchantRaw = merchant(flat)?.take(255),
            upiVpa = vpa,
            paymentMethod = paymentMethod(flat, vpa),
            bankCode = bankCode,
            reference = REFERENCE.find(flat)?.groupValues?.get(1)
        )
    }

    private fun direction(s: String): IngestDirection? {
        val debit = DEBIT_VERBS.find(s)?.range?.first
        val credit = CREDIT_VERBS.find(s)?.range?.first
        return when {
            debit != null && (credit == null || debit < credit) -> IngestDirection.DEBIT
            credit != null -> IngestDirection.CREDIT
            else -> null
        }
    }

    private fun amount(s: String): BigDecimal? {
        val candidates = MONEY.findAll(s).filterNot { m ->
            val before = s.substring(maxOf(0, m.range.first - 18), m.range.first).lowercase()
            NOT_THE_AMOUNT.any { it in before }
        }.toList()
        val raw = candidates.firstOrNull()?.groupValues?.get(1)
            ?: VERB_AMOUNT.find(s)?.groupValues?.get(1) // "debited by 249.0" (SBI, no currency)
            ?: return null
        return raw.replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.setScale(2, java.math.RoundingMode.HALF_UP)
    }

    private fun merchant(s: String): String? =
        (merchantPatterns + COMMON_MERCHANT_PATTERNS).firstNotNullOfOrNull { re ->
            re.find(s)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';')?.takeIf { it.isNotBlank() && it.length >= 2 }
        }

    private fun paymentMethod(s: String, vpa: String?): PaymentMethod? = when {
        vpa != null || Regex("\\bUPI\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) -> PaymentMethod.UPI
        Regex("\\bcredit card\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) -> PaymentMethod.CARD_CREDIT
        Regex("\\b(debit )?card\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) -> PaymentMethod.CARD_DEBIT
        Regex("\\b(ATM|cash withdrawal)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) -> PaymentMethod.CASH
        Regex("\\b(NEFT|IMPS|RTGS|net ?banking)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) -> PaymentMethod.NETBANKING
        else -> null
    }

    companion object {
        private val I = RegexOption.IGNORE_CASE
        private val DEBIT_VERBS = Regex("\\b(debited|spent|sent|paid|withdrawn|purchase|deducted|transferred to)\\b", I)
        private val CREDIT_VERBS = Regex("\\b(credited|received|deposited|refund(?:ed)?|reversed)\\b", I)
        private val MONEY = Regex("(?:Rs\\.?|INR|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
        private val VERB_AMOUNT = Regex("\\b(?:debited|credited) (?:by|with|for) ([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
        private val NOT_THE_AMOUNT = listOf("avl", "avail", "bal", "limit", "outstanding", "min due", "total due")
        private val ACCOUNT = Regex("(?:A/?c|Acct|Account|Card)(?: no\\.?)?(?: ending(?: with)?)?\\s*[X*x]*\\s*(\\d{3,4})\\b", I)
        /** 12-digit UPI/IMPS RRN after a reference label: "Ref 1234…", "Refno", "UPI:", "UPI Ref", "RRN". */
        private val REFERENCE = Regex("(?i)\\b(?:ref(?:erence)?(?: ?no)?|refno|rrn|upi(?: ref)?)\\.?[ :#-]*(\\d{12})(?!\\d)")
        private val VPA = Regex("(?<![A-Za-z0-9._])[a-zA-Z0-9._]{2,}@[a-zA-Z]{2,}\\b")

        private val COMMON_MERCHANT_PATTERNS = listOf(
            Regex("UPI/P2[MA]/\\d+/([^/\\n]+?)(?:/|\\s+Not you|$)", I),     // Axis-style UPI narration
            Regex("\\btrf to ([A-Za-z0-9 &.'*-]+?) Ref", I),                // SBI
            Regex(";\\s*([A-Za-z0-9 &.'*-]+?) credited", I),                // ICICI UPI
            Regex("\\bAt ([A-Za-z][A-Za-z0-9 &.'*-]*?) On\\b", I),            // card spend "At AMAZON On …"
            Regex("\\bon ([A-Za-z][A-Za-z0-9 &'*-]*?)\\.? (?:Avl|Avail)\\b", I),  // card spend "… on AMAZON. Avl Limit" (letter-first: not a date)
            Regex("\\bTo ([A-Za-z0-9 &.'*@-]+?) On\\b", I),                 // HDFC "Sent … To X On"
            Regex("\\bto ([a-zA-Z0-9._-]+@[a-zA-Z]+)\\b", I),               // to VPA
            Regex("\\bfrom (?:VPA )?([A-Za-z0-9 &.'@-]+?)(?: \\(| Ref| on\\b|\\.)", I) // credits
        )
    }
}
