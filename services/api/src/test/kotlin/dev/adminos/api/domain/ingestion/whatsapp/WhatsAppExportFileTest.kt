package dev.adminos.api.domain.ingestion.whatsapp

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WhatsAppExportFileTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun code(bytes: ByteArray) = assertFailsWith<WhatsAppImportException> { WhatsAppExportFile.text(bytes) }.code

    @Test
    fun `plain text is read as UTF-8 without the byte-order mark`() {
        assertEquals("16/10/26 hi ₹", WhatsAppExportFile.text("\uFEFF16/10/26 hi ₹".toByteArray()))
    }

    @Test
    fun `a zip gives its _chat txt, skipping media and macOS metadata, else its only text file`() {
        val withChat = zip("IMG-0001.jpg" to byteArrayOf(1, 0, 2), "__MACOSX/._chat.txt" to byteArrayOf(0), "_chat.txt" to "chat".toByteArray())
        assertEquals("chat", WhatsAppExportFile.text(withChat))
        assertEquals("android", WhatsAppExportFile.text(zip("WhatsApp Chat with HDFC Bank.txt" to "android".toByteArray())))
    }

    @Test
    fun `empty, binary, damaged and chat-less files are rejected with a clear code`() {
        assertEquals("WHATSAPP_EMPTY", code(ByteArray(0)))
        assertEquals("WHATSAPP_NOT_A_CHAT", code(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0, 1)))
        assertEquals("WHATSAPP_NO_CHAT", code(zip("photo.jpg" to byteArrayOf(1))))
        assertEquals("WHATSAPP_UNREADABLE", code(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 9, 9, 9)))
    }

    @Test
    fun `text over 10 MB is refused, inside a zip too, so a zip bomb never inflates fully`() {
        val big = ByteArray(WhatsAppExportFile.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() }
        assertEquals("FILE_TOO_LARGE", code(big))
        assertEquals("FILE_TOO_LARGE", code(zip("_chat.txt" to big)))
    }
}
