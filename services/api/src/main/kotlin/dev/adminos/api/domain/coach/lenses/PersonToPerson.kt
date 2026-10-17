package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.Transaction

/**
 * Is this a UPI payment to a person rather than a shop (AOS-901)? Payments to friends are mostly
 * settle-ups after splitting a bill (the owner's share is imported separately, AOS-911), so they never
 * count in a habit lens: counting them would double the owner's share of a dinner and hide what was
 * really spent.
 *
 * A payee is a person when all of these hold:
 * - it was paid by UPI ID;
 * - its name is not a catalogue brand (a brand is a business, whatever the handle);
 * - the handle's provider is a personal app handle (`@okaxis`, `@oksbi`, `@ybl`, `@paytm`…) or the
 *   handle is a 10-digit mobile number;
 * - the handle carries no merchant hint (Paytm QR, PhonePe `Q…`, BharatPe, Google Pay `gpay-…`, Razorpay…).
 *
 * A heuristic: a shop misjudged as a person is fixed by adding it to the lens (an override wins).
 */
class PersonToPerson(private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT) {

    fun isPersonToPerson(t: Transaction): Boolean {
        val vpa = t.upiVpa?.trim()?.lowercase()?.takeIf { it.contains('@') } ?: return false
        if (catalogue.byBrand(t.merchantName) != null) return false
        val handle = vpa.substringBefore('@')
        val provider = vpa.substringAfter('@')
        if (MERCHANT_HINTS.any { it.containsMatchIn(handle) } || provider in MERCHANT_PROVIDERS) return false
        return provider in PERSONAL_PROVIDERS || MOBILE.matches(handle)
    }

    private companion object {
        /**
         * Handles people get from their UPI app (Google Pay, PhonePe, Paytm, BHIM, WhatsApp…). Bank handles
         * (`@icici`, `@axisbank`) are left out: shops use them as often as people do.
         */
        val PERSONAL_PROVIDERS = setOf(
            "okaxis", "okhdfcbank", "okicici", "oksbi", "ybl", "ibl", "axl", "paytm", "ptyes", "ptaxis", "pthdfc", "ptsbi",
            "apl", "yapl", "upi", "freecharge", "jupiteraxis", "naviaxis", "waicici", "waaxis", "wahdfcbank", "wasbi"
        )

        /** Providers that only issue merchant handles. */
        val MERCHANT_PROVIDERS = setOf("okbizaxis", "okbizicici", "okbizhdfcbank", "okbizsbi", "pty", "fbpe", "mairtel", "rzp", "payu", "cashfree", "yesbankltd")

        val MOBILE = Regex("[6-9]\\d{9}")

        /** Merchant patterns inside a handle: Paytm QR, PhonePe Q-codes, BharatPe, Google Pay business, gateways. */
        val MERCHANT_HINTS = listOf(
            Regex("^paytmqr"), Regex("^paytm[.-]"), Regex("^q\\d{6,}"), Regex("bharatpe"), Regex("^gpay-"), Regex("^bhim[.-]?pay"),
            Regex("razorpay|\\brzp\\b|\\.rzp|rzp\\."), Regex("payu"), Regex("cashfree"), Regex("merchant"), Regex("^mab[.]"),
            Regex("^ezetap|^pinelabs|^mswipe|^instamojo")
        )
    }
}
