package dev.adminos.api.domain.agent.briefing

import dev.adminos.api.domain.agent.BriefingNarration
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiRefusal
import dev.adminos.api.domain.ai.AiResult
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Asks for the optional AI wording of a briefing (AOS-716, D-140), only through [AiGateway]: the flag, the user's
 * consent, the cost cap, the circuit breaker and the audit row all apply there. Returns what to store; never throws
 * for a model or gateway problem, because a failed optional wording must never stop the briefing itself.
 */
class BriefingNarrator(private val gateway: AiGateway) {
    private val logger = LoggerFactory.getLogger(BriefingNarrator::class.java)

    suspend fun narrate(userId: UUID, facts: BriefingFacts): BriefingNarration {
        val result = try {
            gateway.run(BriefingNarrationTask, userId, NarrationInput.of(facts))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Boundary for an optional step (ENGINEERING §2 exception, AOS-716): e.g. the consent lookup failing. The
            // gateway fails closed before any call in that case, so nothing was sent or spent; the template stays.
            logger.warn("Briefing narration for user {} failed before an answer; the template is kept", userId, e)
            return BriefingNarration.Unavailable(AiGateway.INTERNAL)
        }
        val narration = when (result) {
            is AiResult.Answered -> BriefingNarration.Narrated(result.value, result.model)
            is AiResult.Rejected -> BriefingNarration.Rejected(result.code)
            is AiResult.Unavailable -> BriefingNarration.Unavailable(result.code)
            is AiResult.Refused -> when (result.reason) {
                AiRefusal.FEATURE_OFF, AiRefusal.NO_CONSENT -> BriefingNarration.None
                AiRefusal.WITHHELD, AiRefusal.CAP_REACHED -> BriefingNarration.Unavailable(result.reason.code)
            }
        }
        when (narration) {
            is BriefingNarration.Rejected -> logger.info("Briefing narration for user {} rejected ({}); the template is kept", userId, narration.code)
            is BriefingNarration.Unavailable -> logger.info("Briefing narration for user {} unavailable ({}); the template is kept", userId, narration.code)
            else -> Unit
        }
        return narration
    }
}
