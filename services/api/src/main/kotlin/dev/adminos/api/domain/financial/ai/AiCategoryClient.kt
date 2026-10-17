package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Everything that leaves AdminOS for one AI categorization question (AOS-512, D-62, D-33). Nothing else is sent:
 * - [merchant]: the cleaned merchant name the normalizer produced (e.g. "Steam Purchase"). It is never a
 *   person's name: [AiPrivacyGate] only lets business names through.
 * - [paymentMethod]: UPI, card, netbanking… or null.
 * - [allowed]: the fixed category list the answer must come from.
 * Never sent: amounts, dates, account or card numbers, UPI IDs, the raw narration, SMS or email text, or who the user is.
 */
data class AiCategoryQuestion(val merchant: String, val paymentMethod: PaymentMethod?, val allowed: List<TransactionCategory>) {
    init {
        require(merchant.isNotBlank() && merchant.length <= MAX_MERCHANT) { "A merchant name is 1..$MAX_MERCHANT characters" }
        require('@' !in merchant) { "A UPI ID is never sent" }
        require(!LONG_NUMBER.containsMatchIn(merchant)) { "Account, card and reference numbers are never sent" }
        require(allowed.isNotEmpty() && allowed.toSet().size == allowed.size) { "The allowed categories are a non-empty set" }
    }

    companion object {
        const val MAX_MERCHANT = 60
        private val LONG_NUMBER = Regex("\\d{5,}")
    }
}

/** A category the model chose from the allowed list, how sure it is (0–1), and why, in a few words. */
data class AiCategoryAnswer(val category: TransactionCategory, val confidence: Double, val reason: String) {
    init {
        require(confidence in 0.0..1.0) { "Confidence is between 0 and 1, was $confidence" }
        require(reason.isNotBlank() && reason.length <= MAX_REASON) { "A reason is 1..$MAX_REASON characters" }
    }

    companion object {
        const val MAX_REASON = 200
    }
}

/** What asking gave. Only [Answered] can change a transaction; the other two leave it as Other. */
sealed interface AiCategoryResult {
    data class Answered(val answer: AiCategoryAnswer, val model: String) : AiCategoryResult
    /** The model answered, but not in the required shape or not from the list (or refused). Kept as Other. */
    data class Rejected(val why: String) : AiCategoryResult
    /** No answer this time (network, rate limit, server error, open circuit); asked again on a later run. */
    data class Unavailable(val why: String) : AiCategoryResult
}

/** Asks a model for a category. Implementations: the Claude client, the circuit breaker, and test fakes. */
fun interface AiCategoryClient {
    suspend fun ask(question: AiCategoryQuestion): AiCategoryResult
}

/**
 * Stops asking for [openFor] after [failureThreshold] answers in a row were [AiCategoryResult.Unavailable]
 * (circuit breaker, ENGINEERING §6). Any real answer, even a rejected one, closes it again. The SDK below
 * already retries 429/5xx with backoff, so a failure here is a request that failed after its retries.
 */
class CircuitBreakingAiClient(
    private val delegate: AiCategoryClient,
    private val clock: Clock = Clock.systemUTC(),
    private val failureThreshold: Int = 3,
    private val openFor: Duration = Duration.ofMinutes(15)
) : AiCategoryClient {
    private val logger = LoggerFactory.getLogger(CircuitBreakingAiClient::class.java)
    private var consecutiveFailures = 0
    private var openUntil: Instant? = null

    init {
        require(failureThreshold >= 1) { "The failure threshold is at least 1" }
        require(!openFor.isNegative && !openFor.isZero) { "The open period is positive" }
    }

    val isOpen: Boolean @Synchronized get() = openUntil?.let { clock.instant() < it } ?: false

    override suspend fun ask(question: AiCategoryQuestion): AiCategoryResult {
        if (isOpen) return AiCategoryResult.Unavailable("circuit open")
        val result = delegate.ask(question)
        record(result)
        return result
    }

    @Synchronized
    private fun record(result: AiCategoryResult) {
        if (result !is AiCategoryResult.Unavailable) {
            consecutiveFailures = 0
            openUntil = null
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openUntil = clock.instant().plus(openFor)
            consecutiveFailures = 0
            logger.warn("AI categorization paused for {} after {} failed requests in a row ({})", openFor, failureThreshold, result.why)
        }
    }
}
