package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.mandates.MandateFrequency
import dev.adminos.api.domain.financial.mandates.MandateNotice
import dev.adminos.api.domain.financial.mandates.MandateNoticeKind
import dev.adminos.api.domain.financial.mandates.MandateRail
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Reads bank SMS about mandates (AOS-502): set up, the pre-debit notice (NPCI asks banks to warn
 * before each UPI AutoPay debit), paused, resumed, revoked — for UPI AutoPay, e-NACH and card
 * standing instructions. Debits themselves are ordinary transactions (the bank parsers).
 * Only extracted fields leave this class (D-33).
 */
object MandateSmsParser {
    private val I = RegexOption.IGNORE_CASE

    /** The message is about a mandate at all. */
    private val ABOUT_MANDATE = Regex("\\b(mandate|auto-?pay|e-?mandate|e-?nach|nach|ecs|standing instruction|si)\\b", I)

    private val KINDS: List<Pair<Regex, MandateNoticeKind>> = listOf(
        Regex("\\b(revoked|cancell?ed|deactivated|deleted|terminated)\\b", I) to MandateNoticeKind.REVOKED,
        Regex("\\b(resumed|unpaused|re-?activated)\\b", I) to MandateNoticeKind.RESUMED,
        Regex("\\b(paused|suspended|on hold)\\b", I) to MandateNoticeKind.PAUSED,
        Regex("\\b(will be debited|to be debited|will be deducted|is scheduled|scheduled for|due for debit|upcoming debit)\\b", I) to MandateNoticeKind.PRE_DEBIT,
        Regex("\\b(created|registered|set ?up|activated|approved|successful(ly)?|modified|updated)\\b", I) to MandateNoticeKind.CREATED
    )

    /** A debit that has happened is a transaction, not a notice. */
    private val PAST_DEBIT = Regex("\\b(debited|deducted|paid|sent)\\b(?!.*\\b(will|to be)\\b)", I)

    private val MONEY = Regex("(?:Rs\\.?|INR|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", I)
    private val UMN = Regex("\\b(?:UMN|UMRN|Mandate (?:Ref(?:erence)?|ID|No)\\.?)[ :#-]*([A-Za-z0-9@._-]{6,})", I)
    private val PAYEE = listOf(
        Regex("\\b(?:towards|in favou?r of|for merchant|merchant[ :]+)\\s+(?:UPI )?(?:mandate |auto-?pay |e-?mandate )?(?:for )?([A-Za-z][A-Za-z0-9 &.'*-]{1,60}?)(?=\\s+(?:via|on|has|is|of|for|with|UMN|UMRN|ref|will|frequency|valid)\\b|[.,;]|$)", I),
        Regex("\\b(?:mandate|auto-?pay|e-?mandate|NACH|SI)\\s+(?:for|to)\\s+([A-Za-z][A-Za-z0-9 &.'*-]{1,60}?)(?=\\s+(?:via|on|has|is|of|for|with|UMN|UMRN|ref|will|frequency|valid)\\b|[.,;]|$)", I),
        // Last resort, e.g. card standing instructions: "… for Amazon Prime of Rs 1,499"; never "for Rs …"
        Regex("\\bfor (?!(?:Rs|INR|your|the|a)\\b)([A-Z][A-Za-z0-9 &.'*-]{1,60}?)(?=\\s+(?:of|for|has|is|via|on|with|Rs|INR)\\b|\\s*₹|[.,;]|$)")
    )
    private val FREQUENCY: List<Pair<Regex, MandateFrequency>> = listOf(
        Regex("\\bdaily\\b", I) to MandateFrequency.DAILY,
        Regex("\\bfortnightly\\b", I) to MandateFrequency.FORTNIGHTLY,
        Regex("\\bweekly\\b", I) to MandateFrequency.WEEKLY,
        Regex("\\bhalf[- ]?yearly\\b", I) to MandateFrequency.HALF_YEARLY,
        Regex("\\bquarterly\\b", I) to MandateFrequency.QUARTERLY,
        Regex("\\b(yearly|annual(ly)?)\\b", I) to MandateFrequency.YEARLY,
        Regex("\\bmonthly\\b", I) to MandateFrequency.MONTHLY,
        Regex("\\bas (and when )?presented\\b", I) to MandateFrequency.AS_PRESENTED,
        Regex("\\bone[- ]?time\\b", I) to MandateFrequency.ONE_TIME
    )
    private val DEBIT_ON = Regex("\\b(?:on|for)\\s+(\\d{1,2}[-/ ](?:\\d{1,2}|[A-Za-z]{3,9})[-/ ]\\d{2,4})", I)
    private val VALID_UNTIL = Regex("\\bvalid (?:till|until|upto|up to)\\s+(\\d{1,2}[-/ ](?:\\d{1,2}|[A-Za-z]{3,9})[-/ ]\\d{2,4})", I)

    // STRICT: "31/02/2026" is no date at all (SMART would quietly make it 28 Feb).
    private val DATE_FORMATS = listOf("d-M-uuuu", "d/M/uuuu", "d-M-uu", "d/M/uu", "d MMM uuuu", "d-MMM-uuuu", "d-MMM-uu", "d MMMM uuuu", "d MMM uu")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH).withResolverStyle(java.time.format.ResolverStyle.STRICT) }

    /** A payee never starts with these: "mandate for Rs 100…", "for your account…". */
    private val NOT_A_PAYEE = Regex("^(?:for|to|of|rs\\.?|inr|your|the|a|an|has|is|on|via)\\b", I)

    /** @return the notice, or null when the SMS isn't about a mandate (or is a debit that happened) */
    fun parse(text: String, bank: String? = null): MandateNotice? {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (!ABOUT_MANDATE.containsMatchIn(flat)) return null
        val kind = KINDS.firstOrNull { (re, _) -> re.containsMatchIn(flat) }?.second ?: return null
        if (kind == MandateNoticeKind.CREATED && PAST_DEBIT.containsMatchIn(flat)) return null
        val payee = PAYEE.firstNotNullOfOrNull { re ->
            re.findAll(flat).map { it.groupValues[1].trim().trimEnd('.', ',') }.firstOrNull { it.length >= 2 && !NOT_A_PAYEE.containsMatchIn(it) }
        } ?: return null
        return MandateNotice(
            kind = kind,
            rail = rail(flat),
            payee = payee.take(100),
            amount = MONEY.find(flat)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.setScale(2, RoundingMode.HALF_UP),
            umn = UMN.find(flat)?.groupValues?.get(1)?.trimEnd('.', ',', ';', '-'),
            frequency = FREQUENCY.firstOrNull { (re, _) -> re.containsMatchIn(flat) }?.second ?: MandateFrequency.UNKNOWN,
            debitOn = if (kind == MandateNoticeKind.PRE_DEBIT) DEBIT_ON.find(flat)?.groupValues?.get(1)?.let(::date) else null,
            validUntil = VALID_UNTIL.find(flat)?.groupValues?.get(1)?.let(::date),
            bank = bank
        )
    }

    internal fun rail(s: String): MandateRail = when {
        Regex("\\b(UPI|auto-?pay)\\b", I).containsMatchIn(s) && !Regex("\\bcard\\b", I).containsMatchIn(s) -> MandateRail.UPI_AUTOPAY
        Regex("\\b(e-?nach|nach|ecs|umrn)\\b", I).containsMatchIn(s) -> MandateRail.NACH
        Regex("\\b(card|standing instruction|si)\\b", I).containsMatchIn(s) -> MandateRail.CARD_SI
        else -> MandateRail.UNKNOWN
    }

    /** Day first (Indian); two-digit years are 20xx. */
    internal fun date(raw: String): LocalDate? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        for (f in DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f) // "uu" is 2000–2099
            } catch (_: DateTimeParseException) { }
        }
        return null
    }
}

/** Debit SMS that pay a mandate carry a marker (and the UMN) into the transaction, for AOS-502 matching. */
object MandateDebitMarker {
    private val MANDATE_DEBIT = Regex("\\b(mandate|auto-?pay|e-?nach|nach|ecs|standing instruction)\\b", RegexOption.IGNORE_CASE)
    private val UMN = Regex("\\b(?:UMN|UMRN)[ :#-]*([A-Za-z0-9@._-]{6,})", RegexOption.IGNORE_CASE)

    fun metadataFor(text: String): Map<String, String> {
        if (!MANDATE_DEBIT.containsMatchIn(text)) return emptyMap()
        return buildMap {
            put("mandate", "true")
            UMN.find(text)?.groupValues?.get(1)?.trimEnd('.', ',', ';', '-')?.let { put("umn", it) }
        }
    }
}
