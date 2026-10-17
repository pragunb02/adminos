package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.coach.CoachFixture.USER
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-901: which lens a transaction lands in, from the narration a bank prints to the lens. */
class LensMatcherTest {
    private val matcher = LensMatcher()
    private val normalizer = MerchantNormalizer()
    private val categorizer = CategorizationService()

    /** What the pipeline would store for this narration: normalised merchant, category and subcategory. */
    private fun fromBank(raw: String, vpa: String? = null, nature: TransactionNature = TransactionNature.SPEND) =
        normalizer.normalize(raw, vpa).let { merchant ->
            val c = categorizer.builtIn(merchant)
            txn(merchant, nature = nature, category = c.category, subcategory = c.subcategory, vpa = vpa)
        }

    private fun lensesOf(raw: String, vpa: String? = null) = matcher.lensesOf(fromBank(raw, vpa), LensOverrides.NONE).map { it.value }.toSet()

    @Test
    fun `quick commerce apps land in quick commerce`() {
        for (raw in listOf("UPI-ZEPTO-zeptonow@ybl-YESB0000001-428911100001-ORDER", "BLINK COMMERCE PVT LTD", "SWIGGY INSTAMART",
                           "UPI-BBNOW-bbnow@icici-ICIC0000217-428911300017-GROCERY", "FLIPKART MINUTES", "AMAZON NOW", "DUNZO DAILY")) {
            assertEquals(setOf("quick_commerce"), lensesOf(raw), raw)
        }
    }

    @Test
    fun `food delivery apps and any food delivery subcategory land in food delivery`() {
        assertEquals(setOf("food_delivery"), lensesOf("UPI/DR/428911100002/BUNDL TECHNOLOGIES/UTIB/bundl.swiggy@axisbank/Order", "bundl.swiggy@axisbank"))
        assertEquals(setOf("food_delivery"), lensesOf("ZOMATO LTD"))
        assertEquals(setOf("food_delivery"), lensesOf("REBEL FOODS EATSURE"))
        val corrected = txn("Behrouz Biryani", category = TransactionCategory.FOOD, subcategory = "food_delivery")
        assertEquals(setOf(LensId("food_delivery")), matcher.lensesOf(corrected, LensOverrides.NONE))
    }

    @Test
    fun `bars, pubs, clubs and nightlife brands land in nights out`() {
        for (raw in listOf("POS 438765XXXXXX1290 TOIT BREWPUB INDIRANAGAR", "IMPRESARIO ENTERTAINMENT AND HOSPITALITY", "HARD ROCK CAFE",
                           "PAYTM INSIDER EVENTS", "POS 512345XXXXXX4421 THE BIER LIBRARY BREWERY", "SKYYE LOUNGE BAR", "HIGH ULTRA LOUNGE")) {
            assertEquals(setOf("nights_out"), lensesOf(raw), raw)
        }
    }

    @Test
    fun `a keyword matches whole words only`() {
        assertEquals(emptySet(), lensesOf("POS 438765XXXXXX1290 BARBEQUE NATION HOSPITALITY"))
        assertEquals(emptySet(), lensesOf("UPI-DELHI PUBLIC SCHOOL-dpsfees@icici-ICIC0000806-428911900006-SCHOOL FEES", "dpsfees@icici"))
    }

    @Test
    fun `shopping apps and stores land in shopping`() {
        for (raw in listOf("AMAZON SELLER SERVICES", "FLIPKART INTERNET PVT LTD", "MYNTRA DESIGNS", "AJIO", "FSN E COMMERCE VENTURES",
                           "MEESHO", "LIFESTYLE INTERNATIONAL", "ZUDIO", "UNIQLO INDIA", "SHOPPERS STOP", "BRAINBEES SOLUTIONS FIRSTCRY", "PURPLLE")) {
            assertEquals(setOf("shopping"), lensesOf(raw), raw)
        }
        assertEquals(emptySet(), lensesOf("CROMA INFINITI RETAIL"), "electronics aren't the shopping habit by default")
    }

    @Test
    fun `pay-later repayments land in pay later`() {
        for (raw in listOf("LAZYPAY PVT LTD", "UPI-SIMPL-getsimpl@icici-ICIC0000001-428911100009-REPAY", "AMAZON PAY LATER", "FLIPKART PAY LATER",
                           "PAYTM POSTPAID", "ZESTMONEY", "SNAPMINT")) {
            assertEquals(setOf("pay_later"), lensesOf(raw), raw)
        }
        assertEquals(setOf("shopping"), lensesOf("AMAZON PAY INDIA"), "Amazon Pay alone is Amazon")
    }

    @Test
    fun `flights, trains and buses land in travel, city transport does not`() {
        for (raw in listOf("INTERGLOBE AVIATION LTD", "AIR INDIA EXPRESS", "SPICEJET", "IRCTC ETICKETING", "REDBUS", "ABHIBUS", "MAKEMYTRIP INDIA",
                           "LE TRAVENUES TECHNOLOGY IXIGO", "EASEMYTRIP", "YATRA ONLINE")) {
            assertEquals(setOf("travel"), lensesOf(raw), raw)
        }
        for (raw in listOf("BMTC BUS PASS", "DMRC METRO CARD RECHARGE", "UBER INDIA SYSTEMS")) assertEquals(emptySet(), lensesOf(raw), raw)
    }

    @Test
    fun `a person-to-person UPI payment is in no lens even when its category says so`() {
        val settleUp = txn("Rohit Sharma", category = TransactionCategory.ENTERTAINMENT, subcategory = "nightlife", vpa = "rohit.sharma92@oksbi")
        assertEquals(emptySet(), matcher.lensesOf(settleUp, LensOverrides.NONE))
        val namedLikeABar = txn("Pub Crawl Split", vpa = "9800000003@ybl")
        assertEquals(emptySet(), matcher.lensesOf(namedLikeABar, LensOverrides.NONE))
    }

    @Test
    fun `a brand paid by UPI is still the brand`() {
        assertEquals(setOf("quick_commerce"), lensesOf("UPI-ZEPTO-zepto@ybl-YESB0000001-428911100001-ORDER", "zepto@ybl"))
    }

    @Test
    fun `only spending and refunds count`() {
        for (n in listOf(TransactionNature.TRANSFER, TransactionNature.REVERSAL, TransactionNature.INCOME)) {
            assertEquals(emptySet(), matcher.lensesOf(txn("Zepto", nature = n), LensOverrides.NONE), n.name)
        }
        assertEquals(setOf(LensId("quick_commerce")), matcher.lensesOf(txn("Zepto", nature = TransactionNature.REFUND), LensOverrides.NONE))
    }

    @Test
    fun `the owner can add a merchant, even a person, and remove one`() {
        val now = Instant.parse("2026-10-17T10:00:00Z")
        val overrides = LensOverrides(listOf(
            LensOverride.of(USER, LensId("nights_out"), "Rohit Sharma", include = true, at = now),
            LensOverride.of(USER, LensId("quick_commerce"), " ZEPTO ", include = false, at = now),
            LensOverride.of(USER, LensId("shopping"), "Croma", include = true, at = now)
        ))
        val friend = txn("Rohit Sharma", vpa = "rohit.sharma92@oksbi")
        assertEquals(setOf(LensId("nights_out")), matcher.lensesOf(friend, overrides))
        assertEquals(emptySet(), matcher.lensesOf(txn("Zepto"), overrides))
        assertEquals(setOf(LensId("shopping")), matcher.lensesOf(txn("Croma", category = TransactionCategory.SHOPPING, subcategory = "electronics"), overrides))
        assertTrue(matcher.belongsTo(friend, LensId("nights_out"), overrides))
        assertFalse(matcher.belongsTo(friend, LensId("travel"), overrides))
        assertEquals(null, overrides.choiceFor(LensId("nights_out"), null))
    }

    @Test
    fun `an override never matches an unknown merchant and a nameless spend matches only by category`() {
        assertEquals(emptySet(), matcher.lensesOf(txn(null), LensOverrides.NONE))
        val custom = LensMatcher(LensCatalogue(LensFile("t", LateNightEntry("22:00", "05:00"), listOf(LensEntry("fun_money", "Fun", categories = listOf("entertainment"))))))
        assertEquals(setOf(LensId("fun_money")), custom.lensesOf(txn(null, category = TransactionCategory.ENTERTAINMENT), LensOverrides.NONE))
    }

    @Test
    fun `hostile merchant names and regex characters in keywords are matched literally`() {
        val custom = LensMatcher(LensCatalogue(LensFile("t", LateNightEntry("22:00", "05:00"),
            listOf(LensEntry("odd_lens", "Odd", keywords = listOf("(.*)+ bar", "a|b|c"))))))
        assertEquals(emptySet(), custom.lensesOf(txn("Anything At All"), LensOverrides.NONE))
        assertEquals(setOf(LensId("odd_lens")), custom.lensesOf(txn("The Bar"), LensOverrides.NONE))
        val huge = "A".repeat(100_000) + " PUB"
        assertEquals(setOf(LensId("nights_out")), matcher.lensesOf(txn(huge), LensOverrides.NONE))
        assertEquals(emptySet(), matcher.lensesOf(txn("<script>alert('bar')</script>".replace("bar", "x")), LensOverrides.NONE))
    }

    @Test
    fun `the normalised merchant key ignores case and spaces`() {
        assertEquals("zepto", LensOverride.keyOf("  Zepto "))
        val o = LensOverride.of(USER, LensId("travel"), " Goa Trip Bus ", include = true, at = Instant.EPOCH)
        assertEquals("goa trip bus", o.merchantKey)
        assertEquals("Goa Trip Bus", o.merchant)
    }

    @Test
    fun `an override must be built from its own key`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LensOverride(USER, LensId("travel"), "other", "Goa", include = true, updatedAt = Instant.EPOCH)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> { LensOverride.of(USER, LensId("travel"), "   ", include = true, at = Instant.EPOCH) }
    }
}
