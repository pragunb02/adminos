package dev.adminos.api.domain.financial

/**
 * The one way to say "the same merchant" when grouping a user's payments (AOS-713): the
 * normalized merchant name, trimmed and lower-cased. Subscription detection groups by the same key.
 */
object MerchantKey {
    /** @return the key for [merchantName], or null when there is no name to group by */
    fun of(merchantName: String?): String? = merchantName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
