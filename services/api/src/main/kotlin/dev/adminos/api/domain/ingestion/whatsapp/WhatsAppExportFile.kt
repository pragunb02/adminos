package dev.adminos.api.domain.ingestion.whatsapp

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Why an upload can't be read as a WhatsApp export. [message] never quotes the file. */
class WhatsAppImportException(val code: String, override val message: String) : RuntimeException(message)

/**
 * Turns the uploaded file into chat text (AOS-403). WhatsApp shares either `_chat.txt` / `WhatsApp Chat with X.txt`
 * or a `.zip` holding it (iPhone always zips). Only the chat text is read from a zip, and at most
 * [MAX_TEXT_BYTES] of it, so a zip bomb can't exhaust memory. Media in the zip is skipped unread.
 */
object WhatsAppExportFile {
    const val MAX_TEXT_BYTES = 10 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 10_000
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun text(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw WhatsAppImportException("WHATSAPP_EMPTY", "The file is empty")
        val raw = if (isZip(bytes)) chatFromZip(bytes) else bytes
        if (raw.size > MAX_TEXT_BYTES) throw tooLarge()
        if (raw.take(4096).any { it == 0.toByte() }) throw notAnExport() // a binary file (image, PDF) isn't chat text
        return raw.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)

    private fun chatFromZip(bytes: ByteArray): ByteArray = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            var fallback: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw notAnExport()
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.startsWith(".") || !name.endsWith(".txt", ignoreCase = true)) continue
                val content = readCapped(zip)
                if (name.equals("_chat.txt", ignoreCase = true)) return content
                if (fallback == null) fallback = content
            }
            if (entries == 0) throw damaged() // truncated zips read as empty rather than failing
            fallback ?: throw WhatsAppImportException("WHATSAPP_NO_CHAT", "The zip has no chat text (.txt) in it")
        }
    } catch (e: IOException) { // includes ZipException
        throw damaged()
    }

    private fun readCapped(zip: ZipInputStream): ByteArray {
        val content = zip.readNBytes(MAX_TEXT_BYTES + 1)
        if (content.size > MAX_TEXT_BYTES) throw tooLarge()
        return content
    }

    private fun damaged() = WhatsAppImportException("WHATSAPP_UNREADABLE", "The zip file is damaged or encrypted")
    private fun tooLarge() = WhatsAppImportException("FILE_TOO_LARGE", "The chat text is over 10 MB. Export a shorter chat")
    private fun notAnExport() = WhatsAppImportException("WHATSAPP_NOT_A_CHAT", "This doesn't look like a WhatsApp chat export")
}
