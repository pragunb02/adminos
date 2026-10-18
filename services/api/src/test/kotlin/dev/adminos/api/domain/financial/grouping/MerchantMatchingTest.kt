package dev.adminos.api.domain.financial.grouping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-709: cleaning names, scoring similarity and deciding a match. */
class MerchantMatchingTest {
    private fun clean(s: String) = assertNotNull(MerchantNameCleaner.clean(s), s)
    private fun profile(name: String, upi: String? = null, brand: String? = null, business: Boolean = true) =
        MerchantProfile(name = name, key = name.trim().lowercase(), clean = clean(name), payments = 1, upiIds = setOfNotNull(upi), brand = brand, business = business)
    private val matcher = MerchantMatcher()

    // ── Cleaning ──

    @Test
    fun `web, legal and business words go, the distinctive ones stay`() {
        assertEquals(listOf("netflix"), clean("NETFLIX.COM").tokens)
        assertEquals(listOf("netflix"), clean("Netflix Entertainment Services India Pvt Ltd").tokens)
        assertEquals(listOf("amazon", "prime"), clean("Amazon Prime").tokens)
        assertEquals(listOf("kuku", "fm"), clean("KUKU FM INDIA").tokens)
        assertEquals("bigbasket", clean("Big Basket").compact)
        assertEquals("big basket", clean("Big Basket").text)
    }

    @Test
    fun `glued suffixes, gateways, initials and honorifics are cut`() {
        assertEquals(listOf("spotify"), clean("SPOTIFYINDIA").tokens)
        assertEquals(listOf("zomato"), clean("Zomatoonline").tokens)
        assertEquals(listOf("spotify"), clean("Paypal Spotify").tokens)
        assertEquals(listOf("ramesh", "kumar"), clean("RAMESH KUMAR S").tokens)
        assertEquals(listOf("krishna", "dairy"), clean("Shree Krishna Dairy").tokens)
        assertEquals(listOf("zee", "5"), clean("Zee 5").tokens, "a lone digit is distinctive")
        assertEquals(listOf("indi"), clean("Indi").tokens, "a short word keeps its ending")
    }

    @Test
    fun `a name made only of generic words keeps them, and an empty one has no clean form`() {
        assertEquals(listOf("online", "services"), clean("Online Services").tokens)
        assertEquals(listOf("paytm"), clean("PAYTM").tokens, "a gateway alone is the merchant")
        assertNull(MerchantNameCleaner.clean("*** --- ***"))
        assertNull(MerchantNameCleaner.clean(""))
    }

    @Test
    fun `look-alike letters, invisible characters and huge names are flagged or cut`() {
        assertTrue(clean("Netfl\u0456x").confusable, "Cyrillic і")
        assertTrue(clean("Net\u200Bflix").confusable, "zero-width space")
        assertFalse(clean("\uFF2E\uFF25\uFF34\uFF26\uFF2C\uFF29\uFF38").confusable, "full-width NETFLIX folds to plain letters")
        assertEquals(listOf("netflix"), clean("\uFF2E\uFF25\uFF34\uFF26\uFF2C\uFF29\uFF38").tokens)
        assertTrue(clean("a".repeat(100_000) + " b").compact.length <= MerchantNameCleaner.MAX_LENGTH)
        assertFailsWith<IllegalArgumentException> { CleanName(emptyList()) }
        assertFailsWith<IllegalArgumentException> { CleanName(listOf(" ")) }
    }

    // ── Similarity ──

    @Test
    fun `Jaro-Winkler matches the textbook values`() {
        assertEquals(0.9611, JaroWinklerSimilarity.jaroWinkler("martha", "marhta"), 1e-4)
        assertEquals(0.8133, JaroWinklerSimilarity.jaroWinkler("dixon", "dicksonx"), 1e-4)
        assertEquals(1.0, JaroWinklerSimilarity.jaro("same", "same"))
        assertEquals(0.0, JaroWinklerSimilarity.jaro("", "x"))
        assertEquals(0.0, JaroWinklerSimilarity.jaro("abc", "xyz"))
        assertEquals("jaro_winkler", JaroWinklerSimilarity.name)
    }

    @Test
    fun `token-set ratio ignores word order and treats a subset as identical`() {
        assertEquals(1.0, TokenSetSimilarity.score(clean("Kuku FM"), clean("FM Kuku")))
        assertEquals(1.0, TokenSetSimilarity.score(clean("Amazon"), clean("Amazon Pantry")), "why the matcher guards subsets")
        assertTrue(TokenSetSimilarity.score(clean("Calm"), clean("Cult")) < 0.6)
        assertEquals(1.0, TokenSetSimilarity.ratio("", ""))
        assertEquals(0.0, TokenSetSimilarity.ratio("a", ""))
        assertEquals("token_set", TokenSetSimilarity.name)
    }

    // ── Matching ──

    @Test
    fun `the same cleaned name is the strongest match`() {
        val m = assertNotNull(matcher.match(profile("NETFLIX.COM"), profile("Netflix Ent")))
        assertEquals(1.0, m.score)
        assertEquals(setOf(MatchEvidence.SAME_CLEAN_NAME, MatchEvidence.SIMILAR_NAME), m.evidence)
    }

    @Test
    fun `spelling variants match word by word, a different word or start doesn't`() {
        assertNotNull(matcher.match(profile("Annapoorna Mess"), profile("Annapurna Mess")))
        assertNotNull(matcher.match(profile("Swigy"), profile("Swiggy", brand = "Swiggy")))
        assertNull(matcher.match(profile("Amazon Pay Later"), profile("Amazon Pantry")))
        assertNull(matcher.match(profile("Zee News"), profile("Zee Business")))
        assertNull(matcher.match(profile("Hotstar Sports"), profile("Hoichoi")), "different first letters")
        assertNull(matcher.match(profile("Apple Music"), profile("Apple")), "a short name inside a long one")
    }

    @Test
    fun `a shared UPI ID is enough, unless it is a gateway's`() {
        val m = assertNotNull(matcher.match(profile("Anand Kirana", upi = "anandkirana@oksbi"), profile("Sri Anand Stores", upi = "anandkirana@oksbi")))
        assertEquals(setOf(MatchEvidence.SHARED_UPI_ID), m.evidence)
        assertEquals(MerchantMatcher.SHARED_UPI_SCORE, m.score)
        assertNull(matcher.match(profile("Lenskart", upi = "razorpay.payments@axisbank"), profile("Mamaearth", upi = "razorpay.payments@axisbank")))
    }

    @Test
    fun `two known brands never merge, and one known brand is a hint`() {
        assertNull(matcher.match(profile("Uber", brand = "Uber"), profile("Uber Eats", brand = "Uber Eats")))
        val hinted = assertNotNull(matcher.match(profile("Spotifyindia"), profile("Spotify", brand = "Spotify")))
        assertTrue(MatchEvidence.KNOWN_BRAND in hinted.evidence)
    }

    @Test
    fun `people's look-alike names need a shared UPI ID`() {
        assertNull(matcher.match(profile("Amit Patel", business = false), profile("Amita Patel", business = false)))
        assertNotNull(matcher.match(profile("Suresh Babu", upi = "9800000101@ybl", business = false), profile("Suresh B", upi = "9800000101@ybl", business = false)))
        assertNotNull(matcher.match(profile("Ramesh Kumar", business = false), profile("Ramesh Kumar S", business = false)), "the same name once cleaned")
    }

    @Test
    fun `a look-alike or invisible-character name is never proposed, and a merchant isn't its own match`() {
        assertNull(matcher.match(profile("Netfl\u0456x"), profile("Netflix", brand = "Netflix")))
        assertNull(matcher.match(profile("Net\u200Bflix"), profile("Netflix", brand = "Netflix")))
        assertNull(matcher.match(profile("Swiggy"), profile("SWIGGY")))
    }

    @Test
    fun `profiles, matches and settings validate themselves`() {
        assertFailsWith<IllegalArgumentException> { MerchantProfile(" ", "x", clean("x"), 1) }
        assertFailsWith<IllegalArgumentException> { profile("x").copy(payments = -1) }
        assertFailsWith<IllegalArgumentException> { MerchantMatch(1.5, setOf(MatchEvidence.SIMILAR_NAME)) }
        assertFailsWith<IllegalArgumentException> { MerchantMatch(0.9, setOf(MatchEvidence.KNOWN_BRAND)) }
        assertFailsWith<IllegalArgumentException> { MerchantMatcher(threshold = 1.5) }
        assertFailsWith<IllegalArgumentException> { MerchantMatcher(prefix = -1) }
        assertEquals("net", matcher.blockKey(clean("Netflix")))
        assertTrue(profile("Netflix", brand = "Netflix").business)
        assertFalse(MerchantProfile("Asha", "asha", clean("Asha"), 1).business, "no evidence: maybe a person")
    }
}
