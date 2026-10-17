package dev.adminos.api.domain.ai

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Stops calling a provider for [openFor] after [failureThreshold] failed calls in a row (ENGINEERING §6),
 * one circuit per provider. Any real answer, even one that fails validation, closes it again. The clients
 * below already retry 429/5xx with backoff, so a failure here is a request that failed after its retries.
 * Moved from AOS-512's `CircuitBreakingAiClient` with the same settings (3 failures, 15 minutes).
 */
class AiCircuitBreaker(
    private val clock: Clock = Clock.systemUTC(),
    private val failureThreshold: Int = 3,
    private val openFor: Duration = Duration.ofMinutes(15)
) {
    private val logger = LoggerFactory.getLogger(AiCircuitBreaker::class.java)
    private val failures = mutableMapOf<AiProviderKind, Int>()
    private val openUntil = mutableMapOf<AiProviderKind, Instant>()

    init {
        require(failureThreshold >= 1) { "The failure threshold is at least 1" }
        require(!openFor.isNegative && !openFor.isZero) { "The open period is positive" }
    }

    @Synchronized
    fun isOpen(provider: AiProviderKind): Boolean = openUntil[provider]?.let { clock.instant() < it } ?: false

    @Synchronized
    fun record(provider: AiProviderKind, failed: Boolean) {
        if (!failed) {
            failures.remove(provider)
            openUntil.remove(provider)
            return
        }
        val count = (failures[provider] ?: 0) + 1
        if (count < failureThreshold) {
            failures[provider] = count
            return
        }
        failures.remove(provider)
        openUntil[provider] = clock.instant().plus(openFor)
        logger.warn("AI provider {} paused for {} after {} failed calls in a row", provider.key, openFor, failureThreshold)
    }
}
