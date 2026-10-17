package dev.adminos.api.domain.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

/** Which provider and model serve a feature on this server, and at what price (from configuration). */
data class AiRoute(val feature: AiFeature, val provider: AiProvider, val model: String, val price: AiPrice) {
    init {
        require(model.matches(AiCallRecord.ID)) { "'$model' is not a model id" }
    }

    companion object {
        /** A route for [model] on [provider]; refuses a model whose price the provider doesn't know. */
        fun of(feature: AiFeature, provider: AiProvider, model: String): AiRoute {
            val price = provider.price(model)
                ?: throw IllegalArgumentException("${feature.envPrefix}_MODEL=$model has no known price on ${provider.kind.key}; add it to the provider's price table")
            return AiRoute(feature, provider, model, price)
        }
    }
}

/**
 * The single entry point for every model call (D-67, AOS-702). For one feature's [AiTask] it applies, in order:
 * 1. **flag**: no route for the feature → [AiRefusal.FEATURE_OFF] (features are off by default);
 * 2. **redaction**: the task's redactor builds the only text sent, or withholds it → [AiRefusal.WITHHELD];
 * 3. **circuit breaker** per provider → [AiResult.Unavailable] while open;
 * 4. **cost cap**: reserve the worst case, or [AiRefusal.CAP_REACHED] (fails closed);
 * 5. **the provider call**, timed; the reservation is settled to the real cost (or kept whole if it threw);
 * 6. **validation** by the task; only a valid answer is returned as [AiResult.Answered];
 * 7. **audit**: one [AiCallRecord] per provider call, with codes and counts only, never content.
 * Steps 1–4 make no call, so they write no audit row.
 */
class AiGateway(
    routes: List<AiRoute>,
    private val costs: AiCostGuard,
    private val calls: AiCallLogRepository,
    private val breaker: AiCircuitBreaker = AiCircuitBreaker(),
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val routes: Map<AiFeature, AiRoute> = routes.associateBy { it.feature }

    init {
        require(this.routes.size == routes.size) { "One route per feature" }
    }

    /** The route serving [feature], or null when the feature is off on this server. */
    fun route(feature: AiFeature): AiRoute? = routes[feature]

    suspend fun <I, O> run(task: AiTask<I, O>, userId: UUID, input: I): AiResult<O> {
        val route = routes[task.feature] ?: return AiResult.Refused(AiRefusal.FEATURE_OFF)
        val prompt = when (val p = task.prompt(input)) {
            is AiPrompt.Withhold -> return AiResult.Refused(AiRefusal.WITHHELD)
            is AiPrompt.Send -> p
        }
        if (breaker.isOpen(route.provider.kind)) return AiResult.Unavailable(CIRCUIT_OPEN)

        val request = AiRequest(route.model, prompt.system, prompt.user, prompt.schema, prompt.maxOutputTokens)
        val reservation = costs.reserve(route.price.worstCaseMicros(request)) ?: return AiResult.Refused(AiRefusal.CAP_REACHED)
        val started = nanoTime()
        val completion = call(route, request, reservation)
        val latencyMs = (nanoTime() - started).coerceAtLeast(0) / NANOS_PER_MILLI
        breaker.record(route.provider.kind, failed = completion is AiCompletion.Failed)

        val (result, outcome) = judge(task, input, completion, route.model)
        calls.record(AiCallRecord(
            userId = userId, feature = task.feature, provider = route.provider.kind, model = route.model,
            promptVersion = task.promptVersion, usage = completion.usage, costMicros = route.price.costMicros(completion.usage),
            outcome = outcome, code = codeOf(result), latencyMs = latencyMs, at = clock.instant()
        ))
        return result
    }

    /** Calls the provider and settles the reservation; an unexpected throw keeps the whole reservation (fails closed). */
    private suspend fun call(route: AiRoute, request: AiRequest, reservation: AiReservation): AiCompletion {
        var completion: AiCompletion? = null
        try {
            completion = route.provider.complete(request)
            return completion
        } finally {
            // Settled even when cancelled; an unknown cost is charged at its worst case.
            withContext(NonCancellable) { costs.settle(reservation, completion?.let { route.price.costMicros(it.usage) } ?: reservation.micros) }
        }
    }

    private fun <I, O> judge(task: AiTask<I, O>, input: I, completion: AiCompletion, model: String): Pair<AiResult<O>, AiCallOutcome> =
        when (completion) {
            is AiCompletion.Completed -> when (val reading = task.read(completion.text, input)) {
                is AiReading.Valid -> AiResult.Answered(reading.value, model) to AiCallOutcome.ACCEPTED
                is AiReading.Invalid -> AiResult.Rejected(reading.code) to AiCallOutcome.REJECTED
            }
            is AiCompletion.Refused -> AiResult.Rejected(REFUSED) to AiCallOutcome.REJECTED
            is AiCompletion.Truncated -> AiResult.Rejected(TRUNCATED) to AiCallOutcome.REJECTED
            is AiCompletion.Failed -> AiResult.Unavailable(completion.code) to AiCallOutcome.ERROR
        }

    private fun codeOf(result: AiResult<*>): AiReasonCode? = when (result) {
        is AiResult.Answered -> null
        is AiResult.Rejected -> result.code
        is AiResult.Unavailable -> result.code
        is AiResult.Refused -> result.reason.code
    }

    companion object {
        val CIRCUIT_OPEN = AiReasonCode("circuit_open")
        val REFUSED = AiReasonCode("refused")
        val TRUNCATED = AiReasonCode("truncated")
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
