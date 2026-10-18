package dev.adminos.api.domain.documents

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.documents.AesGcmDocumentSealer
import dev.adminos.api.infrastructure.documents.InMemoryDocumentStorage
import dev.adminos.api.infrastructure.documents.UnconfiguredDocumentStorage
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/documents (AOS-507): upload, list, get, download, edit, delete, and every error. */
class DocumentRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val owner = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-507")
    private val stranger = User(id = UUID.randomUUID(), email = "x@example.com", googleId = "g-x")
    private val sealer = AesGcmDocumentSealer(TokenEncryptor(Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))))
    private val repo = InMemoryDocumentRepository()
    private val working = DocumentService(repo, InMemoryDocumentStorage(), sealer)
    private val pdf = "%PDF-1.7\nForm 16".toByteArray()

    private fun routeTest(service: DocumentService = working, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { documentRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.upload(bytes: ByteArray = pdf, fields: Map<String, String> = mapOf("category" to "tax"), as_: User = owner, withFile: Boolean = true) =
        client.submitFormWithBinaryData("/api/v1/documents", formData {
            fields.forEach { (k, v) -> append(k, v) }
            if (withFile) append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream")
                append(HttpHeaders.ContentDisposition, "filename=\"Form16 FY26.pdf\"")
            })
        }) { cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(as_)) }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, json: String? = null, as_: User = owner) =
        client.request("/api/v1$path") {
            this.method = method
            cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(as_))
            json?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private suspend fun HttpResponse.data() = Json.parseToJsonElement(bodyAsText()).jsonObject["data"]!!
    private suspend fun HttpResponse.code() = Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `upload, list, get, download, edit and delete`() = routeTest {
        val created = upload(fields = mapOf("category" to "tax", "expiresOn" to "2027-03-31", "notes" to "employer copy"))
        assertEquals(HttpStatusCode.Created, created.status)
        val d = created.data().jsonObject
        val id = d["id"]!!.jsonPrimitive.content
        assertEquals("Form16 FY26", d["title"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", d["mime"]!!.jsonPrimitive.content)
        assertEquals("2027-03-31", d["expiresOn"]!!.jsonPrimitive.content)
        listOf("wrappedKey", "storageKey", "sha256").forEach { assertTrue(it !in d, "$it is never returned") }

        assertEquals(1, call(HttpMethod.Get, "/documents?category=tax&q=form").data().jsonArray.size)
        assertEquals(0, call(HttpMethod.Get, "/documents?category=vehicle").data().jsonArray.size)
        assertEquals(id, call(HttpMethod.Get, "/documents/$id").data().jsonObject["id"]!!.jsonPrimitive.content)

        val file = call(HttpMethod.Get, "/documents/$id/file")
        assertEquals(HttpStatusCode.OK, file.status)
        assertContentEquals(pdf, file.readRawBytes())
        assertEquals("application/pdf", file.contentType()?.withoutParameters().toString())
        assertTrue(file.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment; filename=\"Form16 FY26.pdf\"; filename*=UTF-8''Form16%20FY26.pdf"))
        assertEquals("no-store", file.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", file.headers["X-Content-Type-Options"])

        val edited = call(HttpMethod.Patch, "/documents/$id", """{"title":"Form 16","category":"bank","expiresOn":"","notes":""}""").data().jsonObject
        assertEquals("Form 16", edited["title"]!!.jsonPrimitive.content)
        assertEquals("bank", edited["category"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, edited["expiresOn"]); assertEquals(JsonNull, edited["notes"])
        assertEquals("2028-01-01", call(HttpMethod.Patch, "/documents/$id", """{"expiresOn":"2028-01-01","notes":"n"}""").data().jsonObject["expiresOn"]!!.jsonPrimitive.content)

        assertEquals(true, call(HttpMethod.Delete, "/documents/$id").data().jsonObject["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/documents/$id").status)
    }

    @Test
    fun `another user's document is simply not found`() = routeTest {
        val id = upload().data().jsonObject["id"]!!.jsonPrimitive.content
        listOf(HttpMethod.Get to "/documents/$id", HttpMethod.Get to "/documents/$id/file", HttpMethod.Delete to "/documents/$id").forEach { (m, p) ->
            val r = call(m, p, as_ = stranger)
            assertEquals(HttpStatusCode.NotFound, r.status, "$m $p"); assertEquals("DOC_001", r.code())
        }
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Patch, "/documents/$id", """{"title":"mine"}""", as_ = stranger).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/documents/not-a-uuid").status)
    }

    @Test
    fun `bad uploads are refused with a reason`() = routeTest {
        assertEquals("DOC_002", upload("#!/bin/sh".toByteArray()).let { assertEquals(HttpStatusCode.UnsupportedMediaType, it.status); it.code() })
        assertEquals("DOC_006", upload(withFile = false).let { assertEquals(HttpStatusCode.BadRequest, it.status); it.code() })
        assertEquals("DOC_007", upload(ByteArray(0)).code())
        assertEquals(HttpStatusCode.PayloadTooLarge, upload(pdf + ByteArray(Document.MAX_BYTES.toInt())).status)
        assertEquals(HttpStatusCode.BadRequest, upload(fields = mapOf("category" to "car")).status)
        assertEquals(HttpStatusCode.BadRequest, upload(fields = mapOf("category" to "tax", "expiresOn" to "31-03-2027")).status)
        assertEquals(HttpStatusCode.BadRequest, upload(fields = mapOf("category" to "tax", "notes" to "x".repeat(1001))).status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/documents?category=car").status)
    }

    @Test
    fun `bad edits are refused`() = routeTest {
        val id = upload().data().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, "/documents/$id", """{"category":"car"}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, "/documents/$id", """{"title":"${"x".repeat(121)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, "/documents/$id", """{"notes":"${"x".repeat(1001)}"}""").status)
    }

    @Test
    fun `without storage the vault says it isn't set up, and storage or integrity failures are explained`() = routeTest(DocumentService(repo, UnconfiguredDocumentStorage(), sealer)) {
        val r = upload()
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status); assertEquals("DOC_005", r.code())
    }

    @Test
    fun `a damaged or missing stored file is a gateway error, a down store is unavailable`() {
        val storage = InMemoryDocumentStorage()
        val service = DocumentService(repo, storage, sealer)
        routeTest(service) {
            val id = upload().data().jsonObject["id"]!!.jsonPrimitive.content
            val d = repo.findById(UUID.fromString(id))!!
            repo.update(d.copy(sha256 = "0".repeat(64)))
            assertEquals("DOC_004", call(HttpMethod.Get, "/documents/$id/file").let { assertEquals(HttpStatusCode.BadGateway, it.status); it.code() })
            storage.delete(d.storageKey)
            assertEquals("DOC_004", call(HttpMethod.Get, "/documents/$id/file").code())
        }
        val down = DocumentService(repo, object : DocumentStorage by storage {
            override suspend fun put(key: String, bytes: ByteArray) = throw DocumentStorageException("down")
        }, sealer)
        routeTest(down) { assertEquals("DOC_003", upload().let { assertEquals(HttpStatusCode.ServiceUnavailable, it.status); it.code() }) }
    }

    @Test
    fun `signed-out calls are refused`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/documents").status)
    }
}
