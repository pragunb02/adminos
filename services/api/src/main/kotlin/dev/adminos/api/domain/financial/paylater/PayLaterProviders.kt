package dev.adminos.api.domain.financial.paylater

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.Transaction

/**
 * A pay-later (BNPL) provider (AOS-909): how its own messages name it ([words], case-insensitive),
 * and how a bank payment to it reads ([narration], over [MerchantCatalogue.matchForm] text).
 */
data class PayLaterProvider(val name: String, val words: Regex, val narration: Regex)

/**
 * The providers, as data: a new one is one entry here (and its aliases in the merchant catalogue).
 * Ordered most specific first: "Amazon Pay Later" before anything that says "Amazon".
 */
object PayLaterProviders {
    private val I = RegexOption.IGNORE_CASE

    val ALL: List<PayLaterProvider> = listOf(
        PayLaterProvider("Amazon Pay Later", Regex("\\bamazon\\s*pay\\s*later\\b", I), Regex("\\bAMAZON ?PAY ?LATER\\b|\\bAPAYLATER\\b")),
        PayLaterProvider("Flipkart Pay Later", Regex("\\bflipkart\\s*pay\\s*later\\b", I), Regex("\\bFLIPKART ?PAY ?LATER\\b|\\bFKPAYLATER\\b")),
        PayLaterProvider("ICICI PayLater", Regex("\\bicici\\s*pay\\s*later\\b", I), Regex("\\bICICI ?PAY ?LATER\\b")),
        PayLaterProvider("Paytm Postpaid", Regex("\\bpaytm\\s*postpaid\\b", I), Regex("\\bPAYTM ?POSTPAID\\b")),
        PayLaterProvider("MobiKwik ZIP", Regex("\\bmobikwik\\s*zip\\b|\\bzip\\s+(?:by|from)\\s+mobikwik\\b", I), Regex("\\bMOBIKWIK ?ZIP\\b")),
        PayLaterProvider("LazyPay", Regex("\\blazy\\s*pay\\b", I), Regex("\\bLAZY ?PAY\\b")),
        PayLaterProvider("Simpl", Regex("\\bsimpl\\b", I), Regex("\\bGET ?SIMPL\\b|\\bSIMPL\\b"))
    )

    /** The provider a message is about, or null. */
    fun namedIn(text: String): PayLaterProvider? = ALL.firstOrNull { it.words.containsMatchIn(text) }

    /** The provider a bank payment goes to (its narration, merchant or UPI ID), or null. */
    fun paidTo(t: Transaction): PayLaterProvider? {
        val texts = listOfNotNull(t.merchantRaw, t.merchantName, t.upiVpa).map(MerchantCatalogue::matchForm)
        return ALL.firstOrNull { p -> texts.any { p.narration.containsMatchIn(it) } }
    }

    fun byName(name: String): PayLaterProvider? = ALL.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}
