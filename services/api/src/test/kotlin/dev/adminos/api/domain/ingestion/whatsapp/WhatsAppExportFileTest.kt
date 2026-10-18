package dev.adminos.api.domain.ingestion.whatsapp

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class WhatsAppExportFileTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun code(bytes: ByteArray, limits: ZipLimits = ZipLimits()) =
        assertFailsWith<WhatsAppImportException> { WhatsAppExportFile.text(bytes, limits) }.code

    /** A clock that moves [step] forward every time it is read, so a slow unpack can be simulated without sleeping. */
    private class SteppingTimeSource(private val step: Duration) : TimeSource {
        private var now = Duration.ZERO
        override fun markNow(): TimeMark {
            val start = now
            return object : TimeMark {
                override fun elapsedNow(): Duration { now += step; return now - start }
            }
        }
    }

    private val chat = "16/10/26, 09:12 - HDFC Bank: Rs 500 debited".toByteArray()

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

    @Test
    fun `a zip bomb hidden in media is stopped by the total inflated cap before the chat is reached`() {
        // 70 MB of zeros deflates to well under 1 MB, so it passes the 20 MB upload cap.
        val bomb = zip("VID-0001.mp4" to ByteArray(70 * 1024 * 1024), "_chat.txt" to chat)
        assertTrue(bomb.size < 1024 * 1024)
        assertEquals("FILE_TOO_LARGE", code(bomb))
    }

    @Test
    fun `many text entries each under 10 MB are stopped by the total inflated cap`() {
        val entries = (1..5).map { "part-$it.txt" to ByteArray(300 * 1024) { 'a'.code.toByte() } }.toTypedArray()
        assertEquals("FILE_TOO_LARGE", code(zip(*entries), ZipLimits(maxInflatedBytes = 1024 * 1024)))
    }

    @Test
    fun `the total counts skipped media and macOS metadata too`() {
        val zip = zip("IMG-0001.jpg" to ByteArray(600 * 1024), "__MACOSX/._x" to ByteArray(600 * 1024), "_chat.txt" to chat)
        assertEquals("FILE_TOO_LARGE", code(zip, ZipLimits(maxInflatedBytes = 1024 * 1024)))
    }

    @Test
    fun `unpacking that takes longer than the time limit is stopped`() {
        val zip = zip("IMG-0001.jpg" to ByteArray(1024 * 1024), "_chat.txt" to chat)
        assertEquals("FILE_TOO_LARGE", code(zip, ZipLimits(maxDuration = 5.seconds, timeSource = SteppingTimeSource(1.seconds))))
    }

    @Test
    fun `an export with photos within the limits is still read`() {
        val zip = zip("IMG-0001.jpg" to ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }, "_chat.txt" to chat)
        assertEquals(chat.decodeToString(), WhatsAppExportFile.text(zip))
    }

    @Test
    fun `the limits must be positive`() {
        assertFailsWith<IllegalArgumentException> { ZipLimits(maxInflatedBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ZipLimits(maxDuration = Duration.ZERO) }
    }
}
