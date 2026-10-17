package dev.adminos.api.domain.ingestion.whatsapp

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One message from a WhatsApp chat export: when (device-local time), who, what. Lives only in memory (D-33). */
data class ChatMessage(val at: LocalDateTime, val sender: String, val text: String) {
    override fun toString(): String = "ChatMessage(at=$at, sender=$sender, text=<redacted ${text.length} chars>)"
}

data class ParsedExport(
    val messages: List<ChatMessage>,
    /** Timestamped lines that aren't from a person or business (e.g. "Messages are end-to-end encrypted"). */
    val systemLines: Int,
    /** Lines that look like the start of a message but couldn't be read (bad date or time). */
    val unreadableLines: Int,
    val dayFirst: Boolean
)

/**
 * Reads WhatsApp "Export chat" text (AOS-403), as iPhone and Android write it:
 * - iPhone: `[16/10/26, 9:12:03 AM] HDFC Bank: Rs.249.00 debited…` (often with an invisible LRM mark and a
 *   narrow no-break space before AM/PM);
 * - Android: `16/10/26, 09:12 - HDFC Bank: Rs.249.00 debited…` (12- or 24-hour).
 * A line without a timestamp continues the previous message. Day/month order is read from the file
 * itself (a first number over 12 means day first); when it can't tell, it's day first (India).
 */
object WhatsAppExportParser {
    private const val SPACE = "[ \\u00A0\\u202F]"
    private const val TIME = "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:$SPACE?([AaPp]\\.?[Mm]\\.?))?"
    private const val DATE = "(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})"
    private val IOS = Regex("^\\[$DATE,$SPACE$TIME]$SPACE(.*)$")
    private val ANDROID = Regex("^$DATE,?$SPACE$TIME$SPACE[-–]$SPACE(.*)$")
    /** "Sender: text" — the sender has no colon; system lines have no sender. */
    private val SENDER = Regex("^([^:]{1,80}):$SPACE?(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val INVISIBLE = Regex("[\\u200E\\u200F\\uFEFF]")

    private data class Head(val d1: Int, val d2: Int, val year: Int, val time: LocalTime?, val rest: String)

    fun parse(content: String): ParsedExport {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.replace(INVISIBLE, "") }
        val heads = lines.map(::head)
        val starts = heads.filterNotNull()
        val dayFirst = when {
            starts.any { it.d1 > 12 } -> true
            starts.any { it.d2 > 12 } -> false
            else -> true
        }

        val messages = mutableListOf<ChatMessage>()
        var system = 0
        var unreadable = 0
        var current: ChatMessage? = null
        for ((i, line) in lines.withIndex()) {
            val h = heads[i]
            if (h == null) {
                // A continuation of the message above (multi-line messages), or noise before the first message.
                current?.let { current = it.copy(text = it.text + "\n" + line) }
                continue
            }
            current?.let { messages += it }
            current = null
            val at = dateTime(h, dayFirst)
            if (at == null) { unreadable++; continue }
            val m = SENDER.find(h.rest)
            if (m == null) { system++; continue }
            current = ChatMessage(at, m.groupValues[1].trim(), m.groupValues[2])
        }
        current?.let { messages += it }
        return ParsedExport(messages.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }, system, unreadable, dayFirst)
    }

    private fun head(line: String): Head? {
        val m = IOS.find(line) ?: ANDROID.find(line) ?: return null
        val g = m.groupValues
        val time = time(g[4], g[5], g[6], g[7])
        val yearRaw = g[3].toInt()
        return Head(g[1].toInt(), g[2].toInt(), if (yearRaw < 100) 2000 + yearRaw else yearRaw, time, g[8])
    }

    private fun time(h: String, m: String, s: String, ampm: String): LocalTime? = try {
        var hour = h.toInt()
        val marker = ampm.lowercase().replace(".", "")
        if (marker == "pm" && hour < 12) hour += 12
        if (marker == "am" && hour == 12) hour = 0
        LocalTime.of(hour, m.toInt(), s.takeIf { it.isNotEmpty() }?.toInt() ?: 0)
    } catch (e: DateTimeException) {
        null
    }

    private fun dateTime(h: Head, dayFirst: Boolean): LocalDateTime? {
        val time = h.time ?: return null
        val (day, month) = if (dayFirst) h.d1 to h.d2 else h.d2 to h.d1
        return try { LocalDate.of(h.year, month, day).atTime(time) } catch (e: DateTimeException) { null }
    }
}
