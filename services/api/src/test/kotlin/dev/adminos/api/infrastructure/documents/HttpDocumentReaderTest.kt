package dev.adminos.api.infrastructure.documents

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.documents.DocumentFileType
import dev.adminos.api.domain.documents.reading.DocumentField
import dev.adminos.api.domain.documents.reading.DocumentReadException
import dev.adminos.api.domain.documents.reading.TextSource
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The parser client (AOS-717): a generic file name, a bounded and validated answer, and our own error words. */
class HttpDocumentReaderTest {
    private fun server(status: Int, body: String, onRequest: (String) -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/documents:read") { ex ->
                onRequest(String(ex.requestBody.readAllBytes(), Charsets.ISO_8859_1))
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}"
    private fun <T> with(s: HttpServer, block: suspend () -> T): T = try { runBlocking { block() } } finally { s.stop(0) }

    @Test
    fun `sends the bytes under a generic name and maps the reading`() {
        var request = ""
        val s = server(200, """{"kind":"pdf","source":"ocr","pagesTotal":3,"pagesRead":3,"truncated":false,"text":"Valid till 31/03/2027",
            "proposals":[{"field":"expiresOn","value":"2027-03-31","evidence":"31/03/2027","label":"Valid till","rule":"expiry_cue"},
            {"field":"somethingNew","value":"x","evidence":"x"}],"extra":1}""") { request = it }
        val r = with(s) { HttpDocumentReader(s.url()).read("%PDF-1.7 synthetic".toByteArray(), DocumentFileType.PDF) }
        assertTrue("filename=\"document.pdf\"" in request && "%PDF-1.7 synthetic" in request)
        assertEquals(TextSource.OCR, r.source)
        assertEquals(listOf(DocumentField.EXPIRES_ON), r.proposals.map { it.field }, "unknown fields are ignored")
        assertFalse("31/03/2027" in r.toString(), "the text never prints")
    }

    @Test
    fun `reader errors map to codes with our own words and the reader down is not the file's fault`() {
        val s = server(422, """{"error":{"code":"IMAGE_TOO_LARGE","message":"<script>Secret Narration</script>"}}""")
        val e = with(s) { assertFailsWith<DocumentReadException> { HttpDocumentReader(s.url()).read(ByteArray(3), DocumentFileType.PNG) } }
        assertEquals("IMAGE_TOO_LARGE", e.code); assertTrue(e.clientError); assertFalse("Secret" in e.message.orEmpty())
        val odd = server(422, """{"error":{"code":"<b>x</b>","message":"m"}}""")
        assertEquals("UNREADABLE", with(odd) { assertFailsWith<DocumentReadException> { HttpDocumentReader(odd.url()).read(ByteArray(3), DocumentFileType.PNG) } }.code)
        val noOcr = server(422, """{"error":{"code":"OCR_UNAVAILABLE","message":"m"}}""")
        assertFalse(with(noOcr) { assertFailsWith<DocumentReadException> { HttpDocumentReader(noOcr.url()).read(ByteArray(3), DocumentFileType.PNG) } }.clientError)
        val broken = server(500, "boom")
        assertFalse(with(broken) { assertFailsWith<DocumentReadException> { HttpDocumentReader(broken.url()).read(ByteArray(3), DocumentFileType.PNG) } }.clientError)
        val e2 = runBlocking { assertFailsWith<DocumentReadException> { HttpDocumentReader("http://127.0.0.1:1").read(ByteArray(3), DocumentFileType.PNG) } }
        assertEquals("READER_UNAVAILABLE", e2.code)
    }

    @Test
    fun `an oversized or malformed answer is refused`() {
        val big = server(200, """{"source":"ocr","pagesTotal":1,"pagesRead":1,"truncated":false,"text":"${"x".repeat(70_000)}","proposals":[]}""")
        assertEquals("READER_ERROR", with(big) { assertFailsWith<DocumentReadException> { HttpDocumentReader(big.url()).read(ByteArray(3), DocumentFileType.PNG) } }.code)
        val tooLong = server(200, """{"source":"ocr","pagesTotal":1,"pagesRead":1,"truncated":false,"text":"${"x".repeat(25_000)}","proposals":[]}""")
        assertEquals("READER_ERROR", with(tooLong) { assertFailsWith<DocumentReadException> { HttpDocumentReader(tooLong.url()).read(ByteArray(3), DocumentFileType.PNG) } }.code)
        val badSource = server(200, """{"source":"cloud","pagesTotal":1,"pagesRead":1,"truncated":false,"text":"","proposals":[]}""")
        assertEquals("READER_ERROR", with(badSource) { assertFailsWith<DocumentReadException> { HttpDocumentReader(badSource.url()).read(ByteArray(3), DocumentFileType.PNG) } }.code)
        val notJson = server(200, "not json")
        assertEquals("READER_ERROR", with(notJson) { assertFailsWith<DocumentReadException> { HttpDocumentReader(notJson.url()).read(ByteArray(3), DocumentFileType.PNG) } }.code)
    }
}
