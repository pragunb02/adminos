package dev.adminos.api.domain.financial.splits

import java.math.BigDecimal
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Why an upload can't be imported. [message] never quotes the file (names and amounts are personal data). */
class SplitImportException(val code: String, override val message: String) : RuntimeException(message)

/** The rows read from one export, from the owner's point of view. */
data class SplitwiseCsvExport(
    val members: Int,
    val entries: List<SharedEntry>,
    /** Rows that didn't add up or had an unreadable date or amount: skipped, counted, never quoted. */
    val unreadableRows: Int
)

/**
 * Reads a Splitwise "Export as spreadsheet" CSV (AOS-911, D-111). Evidence for the format is in the ticket:
 * `Date,Description,Category,Cost,Currency,<member>,<member>,…`, one row per expense or payment, and a final
 * "Total balance" row. Each member's number is the change to their balance: positive when they paid more
 * than their share, negative when they owe. Splitwise adds a BOM and CRLF line endings.
 *
 * Only the owner's column ([me]) is turned into numbers. Other members' names are read only to find the other
 * person in a settle-up; they are never logged and never leave this function except as that counterparty.
 */
object SplitwiseCsv {
    const val MAX_BYTES = 1_000_000
    const val MAX_ROWS = 20_000
    const val MAX_MEMBERS = 60
    const val MAX_FIELD_CHARS = 1_000
    const val MAX_DESCRIPTION_CHARS = 200
    private val HEADER = listOf("date", "description", "category", "cost", "currency")
    private val AMOUNT = Regex("-?\\d{1,10}(\\.\\d{1,2})?")
    private val DATE = Regex("(\\d{4}-\\d{2}-\\d{2})([ T].*)?")
    private val PAYMENT_CATEGORIES = setOf("payment")
    private const val TOTAL_ROW = "total balance"
    /** Each member's number is rounded to paise, so a row may be off by a paisa per member. */
    private val PAISA = BigDecimal("0.01")

    fun parse(bytes: ByteArray, me: String): SplitwiseCsvExport {
        if (bytes.isEmpty()) throw SplitImportException("SPLITWISE_EMPTY", "The file is empty")
        if (bytes.size > MAX_BYTES) throw SplitImportException("FILE_TOO_LARGE", "The file is too large (at most 1 MB). Export one group at a time")
        if (bytes.take(4096).any { it == 0.toByte() }) throw notAnExport()
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        // Room for the header and Splitwise's "Total balance" row.
        val rows = when (val read = CsvRows.read(text, MAX_ROWS + 2, MAX_FIELD_CHARS)) {
            is CsvRows.Read.Rows -> read.rows
            CsvRows.Read.TooManyRows -> throw tooManyRows()
            CsvRows.Read.Malformed -> throw notAnExport()
        }
        val header = rows.firstOrNull() ?: throw notAnExport()
        if (header.size < HEADER.size + 2 || header.take(HEADER.size).map { clean(it).lowercase() } != HEADER) throw notAnExport()
        val members = header.drop(HEADER.size).map(::clean)
        if (members.size > MAX_MEMBERS || members.any { it.isEmpty() }) throw notAnExport()

        val wanted = identity(me)
        if (wanted.isEmpty()) throw SplitImportException("SPLITWISE_ME_REQUIRED", "Choose which column of the export is you")
        val mine = members.indices.filter { identity(members[it]) == wanted }
        if (mine.isEmpty()) throw SplitImportException("SPLITWISE_ME_UNKNOWN", "Your name isn't a column in this export. Choose it from the export's columns")
        if (mine.size > 1) throw SplitImportException("SPLITWISE_ME_AMBIGUOUS", "Two columns in this export have that name")
        val meIndex = mine.single()

        val seen = HashMap<String, Int>()
        val entries = mutableListOf<SharedEntry>()
        var unreadable = 0
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            if (clean(row.getOrElse(1) { "" }).lowercase() == TOTAL_ROW || row[0].isBlank()) continue
            val entry = toEntry(row, members, meIndex, seen)
            if (entry == null) unreadable++ else entries += entry
        }
        return SplitwiseCsvExport(members.size, entries, unreadable)
    }

    private fun toEntry(row: List<String>, members: List<String>, meIndex: Int, seen: MutableMap<String, Int>): SharedEntry? {
        if (row.size != HEADER.size + members.size) return null
        val date = DATE.matchEntire(row[0].trim())?.groupValues?.get(1)?.let {
            try { LocalDate.parse(it) } catch (_: DateTimeParseException) { null }
        } ?: return null
        val description = clean(row[1]).take(MAX_DESCRIPTION_CHARS)
        val category = clean(row[2]).take(60).ifEmpty { null }
        val cost = amount(row[3]) ?: return null
        val currency = clean(row[4]).uppercase()
        if (cost.signum() <= 0 || !currency.matches(Regex("[A-Z]{3}"))) return null
        val values = row.drop(HEADER.size).map { if (it.isBlank()) BigDecimal.ZERO.setScale(2) else amount(it) ?: return null }
        val total = values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.abs() > PAISA.multiply(BigDecimal(values.size))) return null // a row must balance
        val ownerNet = values[meIndex]
        if (ownerNet.abs() > cost) return null
        val kind = if (category?.lowercase() in PAYMENT_CATEGORIES) SplitKind.PAYMENT else SplitKind.EXPENSE

        // Identical rows (two ₹40 chais on one day) are told apart by their order, so a longer export adds only new rows.
        val key = "splitwise|$date|${description.lowercase()}|${cost.toPlainString()}|$currency|$kind"
        val occurrence = seen.merge(key, 1, Int::plus) ?: 1
        return SharedEntry(
            sourceRef = sha256("$key|$occurrence"),
            kind = kind,
            date = date,
            description = description.ifEmpty { if (kind == SplitKind.PAYMENT) "Payment" else "Expense" },
            category = category,
            cost = cost,
            currency = currency,
            ownerNet = ownerNet,
            counterparty = if (kind == SplitKind.PAYMENT) counterparty(values, members, meIndex) else null
        )
    }

    /** The other person in a settle-up: the only other member it moved, or the one whose change mirrors the owner's. */
    private fun counterparty(values: List<BigDecimal>, members: List<String>, meIndex: Int): String? {
        if (values[meIndex].signum() == 0) return null
        val others = values.indices.filter { it != meIndex && values[it].signum() != 0 }
        val mirror = others.filter { values[it].compareTo(values[meIndex].negate()) == 0 }
        return (others.singleOrNull() ?: mirror.singleOrNull())?.let { members[it].take(100) }
    }

    private fun amount(raw: String): BigDecimal? {
        val s = raw.trim()
        return if (AMOUNT.matches(s)) BigDecimal(s).setScale(2) else null
    }

    /** Printable text: control and invisible format characters removed, spaces collapsed. */
    internal fun clean(s: String): String =
        s.filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
            .replace(Regex("\\s+"), " ").trim()

    /** How a name is compared: Unicode-normalised (so look-alike compositions match), case-insensitive. */
    internal fun identity(s: String): String = Normalizer.normalize(clean(s), Normalizer.Form.NFKC).lowercase()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun tooManyRows() = SplitImportException("SPLITWISE_TOO_MANY_ROWS", "The export has more than $MAX_ROWS rows. Export one group at a time")

    private fun notAnExport() = SplitImportException("SPLITWISE_NOT_AN_EXPORT", "This doesn't look like a Splitwise export (Export as spreadsheet, CSV)")
}

/** A small RFC 4180 reader: quoted fields, doubled quotes, CRLF or LF. Linear time; bounded rows and field sizes. */
internal object CsvRows {
    sealed interface Read {
        data class Rows(val rows: List<List<String>>) : Read
        /** An unclosed quote, or a field longer than allowed. */
        data object Malformed : Read
        data object TooManyRows : Read
    }

    fun read(text: String, maxRows: Int, maxField: Int): Read {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        fun endField() { row.add(field.toString()); field.setLength(0) }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
            } else when (c) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(c)
                ',' -> endField()
                '\r' -> {}
                '\n' -> {
                    endField()
                    rows.add(row); row = mutableListOf()
                    if (rows.size > maxRows) return Read.TooManyRows
                }
                else -> field.append(c)
            }
            if (field.length > maxField) return Read.Malformed
            i++
        }
        if (quoted) return Read.Malformed
        if (field.isNotEmpty() || row.isNotEmpty()) { endField(); rows.add(row) }
        return if (rows.size > maxRows) Read.TooManyRows else Read.Rows(rows)
    }
}
