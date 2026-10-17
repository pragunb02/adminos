package dev.adminos.api.infrastructure.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AOS-401 (D-59): the Go Gmail worker reads and writes the tokens this class encrypts. Both sides
 * test the same fixture, `services/workers/internal/tokencrypt/testdata/token_v2_vectors.json`:
 * one value sealed here, one sealed by Go. The key is derived at test time as SHA-256(seed), so no
 * key is stored anywhere; the seed is public and the fixture protects nothing.
 */
class TokenEncryptorCrossLanguageTest {

    private val fixture = File("../workers/internal/tokencrypt/testdata/token_v2_vectors.json")
    private val vector: JsonObject by lazy {
        assertTrue(fixture.isFile, "the shared fixture must exist at ${fixture.absolutePath}")
        Json.parseToJsonElement(fixture.readText()).jsonObject
    }

    private fun field(name: String) = vector.getValue(name).jsonPrimitive.content

    private val encryptor by lazy {
        val key = MessageDigest.getInstance("SHA-256").digest(field("seed").toByteArray(Charsets.UTF_8))
        TokenEncryptor(Base64.getEncoder().encodeToString(key))
    }

    @Test
    fun `the API decrypts what the Go worker encrypted`() {
        assertEquals(field("message"), encryptor.decrypt(field("sealedByGo"), field("aad")))
    }

    @Test
    fun `the API's own vector for the Go worker is valid`() {
        assertEquals(field("message"), encryptor.decrypt(field("sealedByKotlin"), field("aad")))
    }

    @Test
    fun `the Go vector is bound to its context`() {
        assertFailsWith<AEADBadTagException> { encryptor.decrypt(field("sealedByGo"), field("aad") + "x") }
    }

    @Test
    fun `what the API encrypts now round-trips with the fixture key and context`() {
        val sealed = encryptor.encrypt(field("message"), field("aad"))
        assertTrue(sealed.startsWith("v2:"))
        assertEquals(field("message"), encryptor.decrypt(sealed, field("aad")))
    }
}
