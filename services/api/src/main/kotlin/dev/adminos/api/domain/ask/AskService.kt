package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiRefusal
import dev.adminos.api.domain.ai.AiResult
import dev.adminos.api.domain.ai.PiiMasker
import org.slf4j.LoggerFactory
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Whether this user has turned Ask on in Settings → AI features (AOS-704, off by default). The gateway refuses
 * without consent too (`AiRefusal.NO_CONSENT`); asking first means a refused question costs no quota.
 */
fun interface AskConsent {
    suspend fun allows(userId: UUID): Boolean

    companion object {
        /** For tests whose gateway already allows every feature: consent is then decided by the gateway alone. */
        val SERVER_FLAG = AskConsent { true }
    }
}

/** How an answer was made. */
enum class AskSource(val key: String) {
    /** Worded by the model, every number checked against the results. */
    MODEL("model"),

    /** Written by [AskTemplate] from the results, because the model's wording failed or wasn't available. */
    TEMPLATE("template"),

    /** The question can't be answered from spending data; no tool ran. */
    DECLINED("declined"),

    /** The model's plan couldn't be used, or no tool could run. */
    UNANSWERED("unanswered")
}

/** One tool call and what it gave, for "How I got this". [result] is null when [refused] says why it didn't run. */
data class AskStep(val id: String, val call: AskToolCall, val result: AskToolResult?, val refused: AiReasonCode?)

data class AskAnswer(
    val text: String,
    val source: AskSource,
    /** Set when [source] is [AskSource.TEMPLATE]: says that the numbers are shown instead of a worded answer. */
    val notice: String?,
    val declineReason: DeclineReason?,
    val steps: List<AskStep>,
    val comparisons: List<AskComparison>
)

/** Why a question wasn't taken. The API layer maps each to a status and an error code. */
sealed interface AskRejection {
    data object Empty : AskRejection
    data class TooLong(val max: Int) : AskRejection
    data object HiddenCharacters : AskRejection
    data object FeatureOff : AskRejection
    data object NoConsent : AskRejection
    data class RateLimited(val retryAfter: Duration) : AskRejection
    data object CapReached : AskRejection
    data object Unavailable : AskRejection
}

sealed interface AskOutcome {
    /** [askId] names this answer for feedback for a while ([RecentAsks]). */
    data class Answered(val answer: AskAnswer, val askId: java.util.UUID) : AskOutcome
    data class Rejected(val why: AskRejection) : AskOutcome
}

/**
 * "Ask your money" (AOS-710, D-67). For one signed-in user and their zone:
 * 1. checks the question (1..[MAX_QUESTION] characters, no hidden characters), that Ask is on and allowed, and
 *    the per-user [AskQuota];
 * 2. masks personal data in the question ([PiiMasker]) and finds its periods ([PeriodPhrases]);
 * 3. asks the model for a plan through the [AiGateway] only ([AskPlanTask]): read-only tools and typed arguments,
 *    or a decline;
 * 4. runs the tools itself, scoped to the user from the token ([AskScope]); the model never names a user;
 * 5. asks the model to word the answer ([AskAnswerTask]); an answer with any number not in the results is
 *    rejected, and [AskTemplate] writes the answer from the results instead, with a notice.
 * The gateway applies the flag, redaction, breaker, the monthly cost cap and the audit row to both calls.
 * Logs carry ids, counts and codes only, never the question or an amount.
 */
class AskService(
    private val gateway: AiGateway,
    private val runner: AskToolRunner,
    private val quota: AskQuota,
    private val clock: Clock,
    private val consent: AskConsent = AskConsent.SERVER_FLAG,
    private val recent: RecentAsks = RecentAsks(clock)
) {
    companion object {
        const val MAX_QUESTION = 300
        private val log = LoggerFactory.getLogger(AskService::class.java)
    }

    /** Whether Ask can be used on this server (for the web to show or hide the sheet). */
    val enabled: Boolean get() = gateway.route(AiFeature.ASK) != null

    suspend fun ask(userId: UUID, zone: ZoneId, question: String): AskOutcome {
        val text = Normalizer.normalize(question, Normalizer.Form.NFKC).trim().replace(Regex("[\\t\\r\\n ]+"), " ")
        if (text.isEmpty()) return rejected(AskRejection.Empty)
        if (text.length > MAX_QUESTION) return rejected(AskRejection.TooLong(MAX_QUESTION))
        if (AskText.hasUnsafeCharacters(text)) return rejected(AskRejection.HiddenCharacters)
        if (!enabled) return rejected(AskRejection.FeatureOff)
        if (!consent.allows(userId)) return rejected(AskRejection.NoConsent)
        (quota.take(userId) as? AskQuota.Decision.Refused)?.let { return rejected(AskRejection.RateLimited(it.retryAfter)) }

        val today = LocalDate.now(clock.withZone(zone))
        val masked = PiiMasker.mask(text).text
        val plan = when (val r = gateway.run(AskPlanTask, userId, AskPlanInput(masked, today, PeriodPhrases.detect(masked, today)))) {
            is AiResult.Answered -> r.value
            is AiResult.Rejected -> return answered(Asked(userId, masked, null), AskAnswer(AskTemplate.NOT_UNDERSTOOD, AskSource.UNANSWERED, null, null, emptyList(), emptyList()), r.code)
            is AiResult.Unavailable -> return rejected(AskRejection.Unavailable)
            is AiResult.Refused -> return rejected(refusal(r.reason))
        }
        val calls = when (plan) {
            is AskPlan.Decline -> return answered(Asked(userId, masked, plan), AskAnswer(AskTemplate.decline(plan.reason), AskSource.DECLINED, null, plan.reason, emptyList(), emptyList()), null)
            is AskPlan.Tools -> plan.calls
        }

        val scope = AskScope(userId, zone, today)
        val steps = calls.mapIndexed { i, call ->
            when (val o = runner.run(scope, call)) {
                is AskToolOutcome.Ran -> AskStep(AskResultWire.id(i), call, o.result, null)
                is AskToolOutcome.Refused -> AskStep(AskResultWire.id(i), call, null, o.code)
            }
        }
        val results = steps.mapNotNull { it.result }
        if (results.isEmpty()) {
            val onlyFuture = steps.all { it.refused == AskToolRunner.FUTURE_PERIOD }
            return answered(Asked(userId, masked, plan), AskAnswer(if (onlyFuture) AskTemplate.ONLY_PAST else AskTemplate.NOT_UNDERSTOOD, AskSource.UNANSWERED, null, null, steps, emptyList()), null)
        }
        // The model sees only the results that ran, numbered t1, t2… in that order; comparisons index the same list.
        val input = AskAnswerInput(masked, results)
        val comparisons = input.comparisons
        val r = gateway.run(AskAnswerTask, userId, input)
        if (r is AiResult.Answered) return answered(Asked(userId, masked, plan), AskAnswer(r.value, AskSource.MODEL, null, null, steps, comparisons), null)
        // Rejected (failed a check), unavailable, or refused (e.g. the cap was reached between the steps): numbers from code.
        return answered(Asked(userId, masked, plan), template(results, steps, comparisons), (r as? AiResult.Rejected)?.code)
    }

    private fun template(results: List<AskToolResult>, steps: List<AskStep>, comparisons: List<AskComparison>) =
        AskAnswer(AskTemplate.render(results, comparisons), AskSource.TEMPLATE, AskTemplate.FALLBACK_NOTICE, null, steps, comparisons)

    private fun refusal(reason: AiRefusal): AskRejection = when (reason) {
        AiRefusal.FEATURE_OFF -> AskRejection.FeatureOff
        AiRefusal.CAP_REACHED -> AskRejection.CapReached
        // The gateway checks the user's consent itself (AOS-704); this only happens if it changed after [consent] said yes.
        AiRefusal.NO_CONSENT -> AskRejection.NoConsent
        // The redactor only withholds hidden characters, which were refused above; anything else fails closed.
        AiRefusal.WITHHELD -> AskRejection.HiddenCharacters
    }

    private fun rejected(why: AskRejection): AskOutcome {
        log.info("Ask refused: {}", why::class.simpleName)
        return AskOutcome.Rejected(why)
    }

    /** Who asked what (masked) and what the model planned: kept briefly so the user can vote on the answer. */
    private data class Asked(val userId: UUID, val question: String, val plan: AskPlan?)

    private fun answered(asked: Asked, answer: AskAnswer, code: AiReasonCode?): AskOutcome {
        log.info("Ask for {}: {} with {} tool calls{}", asked.userId, answer.source.key, answer.steps.size, code?.let { " ($it)" } ?: "")
        return AskOutcome.Answered(answer, recent.remember(asked.userId, asked.question, asked.plan, answer.source))
    }
}
