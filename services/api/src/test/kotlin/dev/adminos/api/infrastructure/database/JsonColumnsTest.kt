package dev.adminos.api.infrastructure.database

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonColumnsTest {

    @Test
    fun `map with mixed values round-trips to plain kotlin values`() {
        val original = mapOf(
            "s" to "abc", "b" to true, "n" to 42L, "d" to BigDecimal("249.50"), "nil" to null,
            "list" to listOf("x", 1L), "nested" to mapOf("k" to "v")
        )
        assertEquals(original, JsonColumns.decode(JsonColumns.encode(original)))
    }

    @Test
    fun `list of maps round-trips`() {
        val original = listOf(mapOf("category" to "food", "amount" to BigDecimal("2000.00")))
        assertEquals(original, JsonColumns.decodeList(JsonColumns.encodeList(original)))
    }

    @Test
    fun `decimals keep exact scale and precision`() {
        val amount = BigDecimal("12345678901234.10")
        assertEquals(amount, JsonColumns.decode(JsonColumns.encode(mapOf("amount" to amount)))["amount"])
    }

    @Test
    fun `unsupported value types are rejected instead of stringified`() {
        assertFailsWith<IllegalArgumentException> { JsonColumns.encode(mapOf("when" to java.time.Instant.now())) }
    }
}
