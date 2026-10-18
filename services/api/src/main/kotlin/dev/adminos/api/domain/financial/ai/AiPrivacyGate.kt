package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction

/**
 * Decides whether a transaction's payee may be shown to a model at all (AOS-512, D-62, D-33).
 * A UPI or bank transfer to a person carries that person's name, which must never leave AdminOS. So a name
 * is sent only when it is clearly a business:
 * - a card payment (card networks only pay merchants), or
 * - a payment-gateway descriptor (`RAZ*`, `PAYU*`, `BILLDESK*`…), or
 * - a business word in the narration or name ("STORES", "SERVICES", "PVT", "ENTERTAINMENT"…).
 * Anything else, e.g. "UPI-ROHIT SHARMA-…", stays Other and is never asked about. Missing a shop this way
 * is the accepted cost (it stays Other, as it would without AI).
 */
object AiPrivacyGate {

    private val BUSINESS_WORDS = setOf(
        "STORE", "STORES", "SHOP", "MART", "SUPERMARKET", "HYPERMARKET", "BAZAAR", "EMPORIUM", "KIRANA", "GENERAL",
        "TRADERS", "TRADING", "ENTERPRISE", "ENTERPRISES", "AGENCY", "AGENCIES", "SERVICE", "SERVICES", "SOLUTIONS",
        "TECHNOLOGIES", "TECHNOLOGY", "TECH", "LABS", "SYSTEMS", "NETWORK", "NETWORKS", "DIGITAL", "ONLINE", "RETAIL",
        "PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "INC", "CORP", "CORPORATION", "COMPANY", "CO", "INDUSTRIES", "INDIA",
        "CENTRE", "CENTER", "STUDIO", "HOUSE", "HOLIDAYS", "TRAVELS", "TOURS", "ENTERTAINMENT", "GAMING", "GAMES",
        "HOTEL", "HOTELS", "BHAVAN", "SWEETS", "BAKERY", "CAFE", "RESTAURANT", "FOODS", "KITCHEN", "DHABA",
        "ELECTRONICS", "MOBILES", "MOBILE", "RECHARGE", "CABLE", "TV", "FASHION", "BOUTIQUE", "SALON", "SPA",
        "ASSN", "ASSOCIATION", "SOCIETY", "TRUST", "SUBSCRIPTION", "SUBSCR", "PREMIUM", "PURCHASE", "STORAGE", "MEMBERSHIP"
    )
    private val GATEWAY = Regex("^(RAZ|RAZORPAY|RSP|PAYU|CASHFREE|CF|BILLDESK|BD|CCAVENUE|CCA|PTM|JUSPAY|EASEBUZZ|GOOGLE|MICROSOFT|OPENAI)\\s*\\*", RegexOption.IGNORE_CASE)
    private val CARDS = setOf(PaymentMethod.CARD_CREDIT, PaymentMethod.CARD_DEBIT)

    /** The question to ask about [t], or null when its payee may not be shown to a model. */
    fun question(t: Transaction): AiCategoryQuestion? {
        val name = t.merchantName?.trim()?.takeIf { it.length in 3..AiCategoryQuestion.MAX_MERCHANT } ?: return null
        if ('@' in name || !dev.adminos.api.domain.ai.SafeName.isSafe(name) || name.count(Char::isLetter) < 3) return null
        if (!isBusiness(t)) return null
        return AiCategoryQuestion(name, t.paymentMethod, AI_ALLOWED_CATEGORIES)
    }

    /** Positive evidence the payee is a business (also used by merchant grouping, AOS-709). */
    fun isBusiness(t: Transaction): Boolean {
        if (t.paymentMethod in CARDS) return true
        val raw = t.merchantRaw.orEmpty()
        if (GATEWAY.containsMatchIn(raw.trim()) || LEGAL_ENTITY.containsMatchIn(MerchantCatalogue.matchForm(raw))) return true
        // Only the payee's name counts, never the free-text note of a UPI payment: a note saying
        // "MOBILE RECHARGE" on a payment to a friend must not let the friend's name through.
        return MerchantCatalogue.matchForm(t.merchantName.orEmpty()).split(' ').any { it in BUSINESS_WORDS }
    }

    /** A registered company in the narration: "… PVT LTD", "… LIMITED", "… LLP". */
    private val LEGAL_ENTITY = Regex("\\b(PVT|PRIVATE LIMITED|LTD|LIMITED|LLP)\\b")
}
