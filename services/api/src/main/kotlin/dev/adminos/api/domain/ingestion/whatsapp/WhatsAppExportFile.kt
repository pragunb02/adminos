package dev.adminos.api.domain.ingestion.whatsapp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Why an upload can't be read as a WhatsApp export. [message] never quotes the file. */
class WhatsAppImportException(val code: String, override val message: String) : RuntimeException(message)

/**
 * How much work unpacking one zip may take (AOS-625, AUD-20), across **all** its entries: skipping an entry
 * still inflates it, so media and extra text files count too.
 */
data class ZipLimits(
    val maxInflatedBytes: Long = DEFAULT_MAX_INFLATED_BYTES,
    val maxDuration: Duration = DEFAULT_MAX_DURATION,
    val timeSource: TimeSource = TimeSource.Monotonic
) {
    init {
        require(maxInflatedBytes > 0) { "maxInflatedBytes must be positive" }
        require(maxDuration.isPositive()) { "maxDuration must be positive" }
    }

    companion object {
        /** About three times the 20 MB upload cap: room for an export with photos, which barely compress. */
        const val DEFAULT_MAX_INFLATED_BYTES = 64L * 1024 * 1024
        val DEFAULT_MAX_DURATION = 5.seconds
    }
}

/**
 * Turns the uploaded file into chat text (AOS-403). WhatsApp shares either `_chat.txt` / `WhatsApp Chat with X.txt`
 * or a `.zip` holding it (iPhone always zips). Only the chat text is kept from a zip, and at most
 * [MAX_TEXT_BYTES] of it. Unpacking stops as soon as the whole zip has inflated more than
 * [ZipLimits.maxInflatedBytes] or taken longer than [ZipLimits.maxDuration] (AOS-625), so a zip bomb never
 * inflates fully, whether it hides in the chat, in media or in many entries.
 */
object WhatsAppExportFile {
    const val MAX_TEXT_BYTES = 10 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 10_000
    private const val CHUNK = 64 * 1024
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun text(bytes: ByteArray, limits: ZipLimits = ZipLimits()): String {
        if (bytes.isEmpty()) throw WhatsAppImportException("WHATSAPP_EMPTY", "The file is empty")
        val raw = if (isZip(bytes)) chatFromZip(bytes, limits) else bytes
        if (raw.size > MAX_TEXT_BYTES) throw tooLarge()
        if (raw.take(4096).any { it == 0.toByte() }) throw notAnExport() // a binary file (image, PDF) isn't chat text
        return raw.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)

    private fun chatFromZip(bytes: ByteArray, limits: ZipLimits): ByteArray = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val budget = Budget(limits)
            var entries = 0
            var fallback: ByteArray? = null
            while (true) {
                budget.check()
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw notAnExport()
                val name = entry.name.substringAfterLast('/')
                val isText = !entry.isDirectory && !name.startsWith(".") && name.endsWith(".txt", ignoreCase = true)
                // Text is kept only while no chat has been found; everything else is inflated into the budget and dropped.
                val keep = isText && (fallback == null || name.equals("_chat.txt", ignoreCase = true))
                val content = drain(zip, budget, keep)
                if (!keep) continue
                if (name.equals("_chat.txt", ignoreCase = true)) return content
                fallback = content
            }
            if (entries == 0) throw damaged() // truncated zips read as empty rather than failing
            fallback ?: throw WhatsAppImportException("WHATSAPP_NO_CHAT", "The zip has no chat text (.txt) in it")
        }
    } catch (e: IOException) { // includes ZipException
        throw damaged()
    }

    /**
     * Reads the current entry to its end in [CHUNK]s, charging every inflated byte to [budget]. Keeps the bytes
     * only when [keep], and then refuses more than [MAX_TEXT_BYTES].
     */
    private fun drain(zip: ZipInputStream, budget: Budget, keep: Boolean): ByteArray {
        val kept = if (keep) ByteArrayOutputStream() else null
        val buffer = ByteArray(CHUNK)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            budget.spend(n)
            if (kept != null) {
                if (kept.size() + n > MAX_TEXT_BYTES) throw tooLarge()
                kept.write(buffer, 0, n)
            }
        }
        return kept?.toByteArray() ?: EMPTY
    }

    /** The inflated bytes and time one zip has used so far. */
    private class Budget(private val limits: ZipLimits) {
        private val started: TimeMark = limits.timeSource.markNow()
        private var inflated = 0L

        fun spend(bytes: Int) {
            inflated += bytes
            if (inflated > limits.maxInflatedBytes) throw zipTooBig()
            check()
        }

        fun check() {
            if (started.elapsedNow() > limits.maxDuration) throw zipTooBig()
        }
    }

    private val EMPTY = ByteArray(0)
    private fun damaged() = WhatsAppImportException("WHATSAPP_UNREADABLE", "The zip file is damaged or encrypted")
    private fun tooLarge() = WhatsAppImportException("FILE_TOO_LARGE", "The chat text is over 10 MB. Export a shorter chat")
    private fun zipTooBig() =
        WhatsAppImportException("FILE_TOO_LARGE", "The zip is too large to unpack. Export the chat without media")
    private fun notAnExport() = WhatsAppImportException("WHATSAPP_NOT_A_CHAT", "This doesn't look like a WhatsApp chat export")
}
