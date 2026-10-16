package dev.adminos.api.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-608: no secret ever defaults to a value that is readable in this public repo. */
class AppConfigSecretsTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    @Test
    fun `a real secret from the environment is used as is (trimmed)`() {
        assertEquals("s3cr3t-value", AppConfig.secret("JWT_SECRET", env("JWT_SECRET" to " s3cr3t-value\n")) { "dev" })
    }

    @Test
    fun `with a database, a missing or placeholder secret stops start-up`() {
        for (value in listOf(null, "", "CHANGE-ME-generate-with-openssl-rand-base64-32", "change-me-in-production-must-be-32-bytes",
                             "generate_a_256_bit_random_secret_here", "your_anthropic_api_key")) {
            val vars = listOfNotNull("DATABASE_URL" to "jdbc:postgresql://db/adminos", value?.let { "TOKEN_ENCRYPTION_KEY" to it })
            val e = assertFailsWith<IllegalStateException>(value) { AppConfig.secret("TOKEN_ENCRYPTION_KEY", env(*vars.toTypedArray())) { "dev" } }
            assertTrue(e.message!!.contains("TOKEN_ENCRYPTION_KEY is required when DATABASE_URL is set"))
        }
    }

    @Test
    fun `without a database (in-memory dev) a random per-process secret is used`() {
        assertEquals("dev-random", AppConfig.secret("JWT_SECRET", env()) { "dev-random" })
        assertEquals("dev-random", AppConfig.secret("JWT_SECRET", env("JWT_SECRET" to "change-me")) { "dev-random" })
    }

    @Test
    fun `dev secrets are random, strong, and stable for the process`() {
        assertEquals(DevSecrets.jwtSecret, DevSecrets.jwtSecret)
        assertEquals(48, java.util.Base64.getDecoder().decode(DevSecrets.jwtSecret).size)
        assertEquals(32, java.util.Base64.getDecoder().decode(DevSecrets.tokenEncryptionKey).size)
        dev.adminos.api.infrastructure.crypto.TokenEncryptor(DevSecrets.tokenEncryptionKey) // a valid key
    }

    @Test
    fun `placeholders are recognised, real values are not`() {
        assertTrue(AppConfig.isPlaceholder("CHANGE_ME"))
        assertTrue(!AppConfig.isPlaceholder("q8Zt1u2rS0Vx+Y3w4E5f6G7h8I9j0K1l2M3n4O5p6Q="))
    }
}
