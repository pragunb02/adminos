package dev.adminos.api.domain.ai

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-635: a model's reply is checked for size and nesting depth before it is parsed. */
class AiJsonTest {

    @Test
    fun `a flat object is parsed`() {
        assertEquals("a", AiJson.parseObject("""  {"k":"a","n":1} """, maxDepth = 1)?.get("k")?.jsonPrimitive?.content)
    }

    @Test
    fun `deeply nested replies are refused without parsing or throwing`() {
        for (hostile in listOf("[".repeat(10_000), "{\"a\":".repeat(5_000), "[".repeat(10_000) + "]".repeat(10_000))) {
            assertNull(AiJson.parseObject(hostile, maxDepth = 1))
            assertNull(AiJson.parseObject(hostile, maxDepth = 8))
        }
    }

    @Test
    fun `nesting deeper than allowed is refused, and within it accepted`() {
        val twoLevels = """{"a":{"b":1}}"""
        assertNull(AiJson.parseObject(twoLevels, maxDepth = 1))
        assertEquals(setOf("a"), AiJson.parseObject(twoLevels, maxDepth = 2)?.keys)
        assertNull(AiJson.parseObject("""{"a":[1]}""", maxDepth = 1))
    }

    @Test
    fun `brackets and escaped quotes inside strings don't count as nesting`() {
        val text = """{"reason":"[[[{{{ \" ]]] \\"}"""
        assertFalse(AiJson.depthExceeds(text, 1))
        assertEquals(setOf("reason"), AiJson.parseObject(text, maxDepth = 1)?.keys)
    }

    @Test
    fun `an oversized reply is refused before it is read`() {
        val big = """{"reason":"${"x".repeat(AiJson.MAX_REPLY_CHARS)}"}"""
        assertNull(AiJson.parseObject(big, maxDepth = 1))
        assertEquals(setOf("r"), AiJson.parseObject("""{"r":"${"x".repeat(100)}"}""", maxDepth = 1, maxChars = 200)?.keys)
        assertNull(AiJson.parseObject("""{"r":"${"x".repeat(300)}"}""", maxDepth = 1, maxChars = 200))
    }

    @Test
    fun `malformed or non-object replies are null`() {
        for (bad in listOf("", "   ", "not json", "[1,2]", "\"text\"", "42", "{\"a\":", "{\"a\":1}}", "{'a':1}", "\u0000")) {
            assertNull(AiJson.parseObject(bad, maxDepth = 1), bad)
        }
    }

    @Test
    fun `an unbalanced closing bracket never opens a deeper level`() {
        assertFalse(AiJson.depthExceeds("]]]]{}", 1))
        assertTrue(AiJson.depthExceeds("{[", 1))
    }

    @Test
    fun `a depth below one is a programming error`() {
        assertFailsWith<IllegalArgumentException> { AiJson.parseObject("{}", maxDepth = 0) }
    }
}
