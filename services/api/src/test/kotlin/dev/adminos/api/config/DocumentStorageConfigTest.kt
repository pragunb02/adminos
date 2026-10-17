package dev.adminos.api.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStorageConfigTest {
    private val secret = java.util.UUID.randomUUID().toString()
    private fun from(vararg env: Pair<String, String>) = DocumentStorageConfig.from { env.toMap()[it] }
    private val r2 = arrayOf("DOCS_S3_BUCKET" to "adminos-docs", "DOCS_S3_ENDPOINT" to "https://abc123.r2.cloudflarestorage.com/",
        "DOCS_S3_ACCESS_KEY_ID" to "key-id", "DOCS_S3_SECRET_ACCESS_KEY" to secret)

    @Test
    fun `off without a bucket, R2 defaults to region auto`() {
        assertNull(from())
        val c = from(*r2)!!
        assertEquals("auto", c.region)
        assertEquals("https://abc123.r2.cloudflarestorage.com", c.endpoint)
        assertFalse(secret in c.toString())
        assertTrue("adminos-docs" in c.toString())
        assertEquals("eu-west-1", from(*r2, "DOCS_S3_REGION" to "eu-west-1")!!.region)
    }

    @Test
    fun `missing or unsafe settings stop start-up with a message naming the setting`() {
        listOf(
            arrayOf("DOCS_S3_BUCKET" to "adminos-docs") to "DOCS_S3_ENDPOINT",
            arrayOf(*r2, "DOCS_S3_ENDPOINT" to "http://r2.example.com") to "DOCS_S3_ENDPOINT",
            arrayOf(*r2, "DOCS_S3_BUCKET" to "Bad_Bucket") to "DOCS_S3_BUCKET",
            arrayOf("DOCS_S3_BUCKET" to "adminos-docs", "DOCS_S3_ENDPOINT" to "https://x.r2.cloudflarestorage.com") to "DOCS_S3_ACCESS_KEY_ID",
        ).forEach { (env, key) ->
            val e = assertFailsWith<IllegalArgumentException>(env.toList().toString()) { from(*env) }
            assertTrue(e.message.orEmpty().contains(key), e.message)
        }
        assertEquals("http://127.0.0.1:9000", from(*r2, "DOCS_S3_ENDPOINT" to "http://127.0.0.1:9000")!!.endpoint, "plain http only for a local test store")
    }
}
