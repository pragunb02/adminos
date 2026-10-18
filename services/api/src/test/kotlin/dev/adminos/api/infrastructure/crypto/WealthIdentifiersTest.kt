package dev.adminos.api.infrastructure.crypto

import dev.adminos.api.domain.wealth.WealthFixtures
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** AOS-802: wealth identifiers are encrypted under their own derived key, bound to their row, and indexed blindly. */
class WealthIdentifiersTest {
    private val hex = HexFormat.of()
    private val ids = WealthIdentifiers(WealthFixtures.testKey())
    private val ctx = "investment_accounts:11111111-1111-1111-1111-111111111111:identifier"

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val prk = Hkdf.extract(hex.parseHex("000102030405060708090a0b0c"), ByteArray(22) { 0x0b })
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex.formatHex(prk))
        val okm = Hkdf.expand(prk, hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex.formatHex(okm))
    }

    @Test
    fun `HKDF matches RFC 5869 test case 3 with no salt and no info`() {
        val prk = Hkdf.extract(ByteArray(0), ByteArray(22) { 0x0b })
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", hex.formatHex(prk))
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", hex.formatHex(Hkdf.expand(prk, ByteArray(0), 42)))
        assertFailsWith<IllegalArgumentException> { Hkdf.expand(prk, ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { Hkdf.expand(prk, ByteArray(0), 255 * 32 + 1) }
    }

    @Test
    fun `an identifier round-trips and only its last 4 are kept in clear`() {
        val p = ids.protect(IdentifierKind.FOLIO, "9000 1234 / 56", ctx)
        assertTrue(p.ciphertext.startsWith("v2:"))
        assertFalse("90001234" in p.ciphertext)
        assertEquals("4/56", p.last4)
        assertEquals("90001234/56", ids.reveal(p, ctx))
        assertEquals("••••4/56", p.masked)
    }

    @Test
    fun `a ciphertext copied to another row does not decrypt`() {
        val p = ids.protect(IdentifierKind.FOLIO, "90001234", ctx)
        assertFailsWith<javax.crypto.AEADBadTagException> { ids.reveal(p, "investment_accounts:22222222-2222-2222-2222-222222222222:identifier") }
    }

    @Test
    fun `encrypting twice gives different ciphertexts but the same blind index`() {
        val a = ids.protect(IdentifierKind.FOLIO, "90001234", ctx)
        val b = ids.protect(IdentifierKind.FOLIO, "90001234", ctx)
        assertNotEquals(a.ciphertext, b.ciphertext)
        assertEquals(a.blindIndex, b.blindIndex)
        assertEquals(a.blindIndex, ids.index(IdentifierKind.FOLIO, " 9000 1234 "))
    }

    @Test
    fun `the blind index separates kinds and depends on the key`() {
        assertNotEquals(ids.index(IdentifierKind.FOLIO, "1234567890"), ids.index(IdentifierKind.PRAN, "1234567890"))
        val other = WealthIdentifiers(WealthFixtures.testKey("another-seed"))
        assertNotEquals(ids.index(IdentifierKind.FOLIO, "1234567890"), other.index(IdentifierKind.FOLIO, "1234567890"))
        // Not a plain hash of the value: without the key it can't be recomputed from a database copy.
        val plain = hex.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("FOLIO:1234567890".toByteArray()))
        assertNotEquals(plain, ids.index(IdentifierKind.FOLIO, "1234567890"))
    }

    @Test
    fun `the wealth key is not the token key`() {
        val p = ids.protect(IdentifierKind.FOLIO, "90001234", ctx)
        val tokenKey = TokenEncryptor(WealthFixtures.testKey())
        assertFailsWith<javax.crypto.AEADBadTagException> { tokenKey.decrypt(p.ciphertext, ctx) }
    }

    @Test
    fun `hostile or oversized identifiers are refused before anything is stored`() {
        for (bad in listOf("", "   ", "1".repeat(41), "123<script>", "12'; DROP TABLE x;--", "../../etc", "१२३४", "12\u0000 34", "ab‮cd")) {
            assertFailsWith<IllegalArgumentException>(bad) { ids.protect(IdentifierKind.FOLIO, bad, ctx) }
        }
        assertEquals("", ids.protect(IdentifierKind.FOLIO, "1234", ctx).last4, "a 4-character identifier shows nothing")
    }

    @Test
    fun `a master key that is not 32 random bytes stops start-up`() {
        assertFailsWith<IllegalArgumentException> { WealthIdentifiers("not base64 !!") }
        assertFailsWith<IllegalArgumentException> { WealthIdentifiers(java.util.Base64.getEncoder().encodeToString(ByteArray(16))) }
    }
}
