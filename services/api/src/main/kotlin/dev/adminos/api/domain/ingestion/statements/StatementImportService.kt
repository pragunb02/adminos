package dev.adminos.api.domain.ingestion.statements

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.balances.BalanceObservation
import dev.adminos.api.domain.balances.BalanceRecordResult
import dev.adminos.api.domain.balances.BalanceService
import dev.adminos.api.domain.balances.BalanceSource
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class StatementImportResult(
    val bankCode: String?,
    val accountLast4: String?,
    val periodStart: java.time.LocalDate?,
    val periodEnd: java.time.LocalDate?,
    val rows: Int,
    val accepted: Int,
    val duplicates: Int,
    val skippedRows: Int,
    val warnings: List<String>,
    /** The closing balance updated the account's latest balance (AOS-720). */
    val balanceNoted: Boolean = false
)

/**
 * Statement adapter (D-21, D-37): parsed rows → intake events. Each row's `sourceRef` is a fingerprint of
 * bank, account, date, direction, amount, description and balance, plus an occurrence counter for
 * identical lines in the same file. Re-uploading a statement, or an overlapping one, adds only new rows.
 */
class StatementImportService(
    private val parser: StatementParser,
    private val intake: IngestEventService,
    private val balances: BalanceService,
    private val clock: Clock = Clock.systemUTC()
) {

    companion object {
        /** Statements carry dates, not times: use midday India time so the day never shifts across time zones. */
        private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
        private val MIDDAY: LocalTime = LocalTime.NOON
        private const val CHUNK = 100
    }

    suspend fun import(userId: UUID, file: ByteArray, filename: String, password: String?): StatementImportResult {
        val parsed = parser.parse(file, filename, password)
        val seen = HashMap<String, Int>()
        val events = parsed.rows.mapNotNull { row ->
            val (direction, amount) = when {
                row.debit != null && row.credit == null -> IngestDirection.DEBIT to row.debit
                row.credit != null && row.debit == null -> IngestDirection.CREDIT to row.credit
                else -> return@mapNotNull null // the parser guarantees one side; defensive
            }
            if (amount.signum() <= 0) return@mapNotNull null
            val key = listOf(parsed.bankCode, parsed.accountLast4, row.date, direction, amount.toPlainString(),
                row.description.trim().lowercase(), row.balance?.toPlainString()).joinToString("|")
            val occurrence = seen.merge(key, 1, Int::plus)!!
            IngestEvent(
                userId = userId,
                source = IngestSource.STATEMENT,
                sourceRef = sha256("statement|$key|#$occurrence"),
                occurredAt = row.date.atTime(MIDDAY).atZone(ZONE).toInstant(),
                direction = direction,
                amount = amount.setScale(2),
                merchantRaw = row.description.take(255),
                accountHint = parsed.accountLast4,
                upiVpa = VPA.find(row.description)?.value?.lowercase(),
                paymentMethod = paymentMethodOf(row.description),
                paymentRef = row.reference?.trim()?.takeIf { RRN_EXACT.matches(it) } ?: RRN_IN_TEXT.find(row.description)?.value,
                // The running balance stays out of the event (it's sensitive and unused there): the closing one is
                // kept, encrypted, as the account's balance (AOS-720).
                metadata = parsed.bankCode?.let { mapOf("bank" to it) }
            )
        }
        var accepted = 0
        var duplicates = 0
        events.chunked(CHUNK).forEach { batch ->
            val r = intake.ingest(batch)
            accepted += r.accepted
            duplicates += r.duplicates
        }
        return StatementImportResult(
            bankCode = parsed.bankCode, accountLast4 = parsed.accountLast4,
            periodStart = parsed.periodStart, periodEnd = parsed.periodEnd,
            rows = events.size, accepted = accepted, duplicates = duplicates,
            skippedRows = parsed.skippedRows + (parsed.rows.size - events.size), warnings = parsed.warnings,
            balanceNoted = noteClosingBalance(userId, parsed)
        )
    }

    /** The statement's closing balance becomes its account's balance when it reconciles (AOS-720, D-170). */
    private suspend fun noteClosingBalance(userId: UUID, parsed: ParsedStatement): Boolean {
        val last4 = parsed.accountLast4 ?: return false
        val closing = (StatementClosingBalance.of(parsed, clock.instant()) as? ClosingBalanceResult.Found)?.closing ?: return false
        // Identity: the account, the day and the figure, so re-importing the same statement records once.
        val ref = sha256(listOf("statement-balance", parsed.bankCode, last4, closing.on, closing.amount.toPlainString()).joinToString("|"))
        val r = balances.record(BalanceObservation(userId, parsed.bankCode, last4, closing.amount, closing.observedAt, BalanceSource.STATEMENT, ref))
        return r == BalanceRecordResult.Saved
    }

    /** Statement narrations name the rail: "UPI-…", "POS …", "NEFT CR-…", "ATM WDL". */
    private fun paymentMethodOf(narration: String): PaymentMethod? {
        val n = narration.uppercase()
        return when {
            Regex("\\bUPI\\b").containsMatchIn(n) -> PaymentMethod.UPI
            Regex("\\b(POS|ECOM|CARD|VISA|MASTERCARD|RUPAY)\\b").containsMatchIn(n) -> PaymentMethod.CARD_DEBIT
            Regex("\\b(ATM|CASH WDL)\\b").containsMatchIn(n) -> PaymentMethod.CASH
            Regex("\\b(NEFT|IMPS|RTGS|NACH|ECS)\\b").containsMatchIn(n) -> PaymentMethod.NETBANKING
            else -> null
        }
    }

    private val RRN_EXACT = Regex("\\d{12}")
    private val RRN_IN_TEXT = Regex("(?<!\\d)\\d{12}(?!\\d)")

    private val VPA = Regex("(?<![A-Za-z0-9._])[a-zA-Z0-9._]{2,}@[a-zA-Z]{2,}\\b") // no "-": narrations use it as a separator

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
