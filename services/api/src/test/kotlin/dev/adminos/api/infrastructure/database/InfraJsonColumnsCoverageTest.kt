package dev.adminos.api.infrastructure.database

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** JsonColumns edge cases (AOS-312): pre-built JSON values, non-string keys, unsupported types. */
class InfraJsonColumnsCoverageTest {

    @Test
    fun `JSON elements are stored as they are`() {
        val text = JsonColumns.encode(mapOf("bank" to JsonPrimitive("hdfc"), "upi" to buildJsonObject { put("vpa", "ramu@ybl") }))
        assertEquals(mapOf("bank" to "hdfc", "upi" to mapOf("vpa" to "ramu@ybl")), JsonColumns.decode(text))
    }

    @Test
    fun `nested maps, lists, numbers and booleans round-trip`() {
        val value = mapOf("n" to 3, "ok" to true, "none" to null, "amounts" to listOf(BigDecimal("249.50")), "nested" to mapOf("k" to "v"))
        val decoded = JsonColumns.decode(JsonColumns.encode(value))
        assertEquals(3L, decoded["n"]); assertEquals(true, decoded["ok"]); assertEquals(null, decoded["none"])
        assertEquals(listOf(BigDecimal("249.50")), decoded["amounts"]); assertEquals(mapOf("k" to "v"), decoded["nested"])
    }

    @Test
    fun `a nested map with a non-string key is rejected and names the key type`() {
        val e = assertFailsWith<IllegalArgumentException> { JsonColumns.encode(mapOf("m" to mapOf(1 to "x"))) }
        assertTrue("Int" in e.message!!, e.message)
    }

    @Test
    fun `a nested map with a null key is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { JsonColumns.encode(mapOf("m" to mapOf(null to "x"))) }
        assertTrue("null" in e.message!!, e.message)
    }

    @Test
    fun `unsupported values are rejected rather than stringified`() {
        assertFailsWith<IllegalArgumentException> { JsonColumns.encode(mapOf("d" to LocalDate.of(2026, 10, 16))) }
    }

    @Test
    fun `lists of maps round-trip`() {
        val list = listOf(mapOf("category" to "food"), mapOf("category" to "shopping"))
        assertEquals(list, JsonColumns.decodeList(JsonColumns.encodeList(list)))
    }
}
