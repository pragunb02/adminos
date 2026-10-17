package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.RateLimiter
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** A user's learned categoriser, as the web shows it (AOS-707). Ratios are 0–1; there are no amounts here. */
@Serializable
data class CategoryModelStatusResponse(
    val enabled: Boolean,
    /** off / cold_start / ready / corrupt */
    val state: String,
    /** apply / suggest, when ready */
    val mode: String? = null,
    val trainedAt: String? = null,
    val corrections: Int,
    val correctionsNeeded: Int,
    val trainingRows: Int? = null,
    val heldOut: Int? = null,
    val heldOutPrecision: Double? = null,
    val calibrationError: Double? = null
) {
    companion object {
        fun from(enabled: Boolean, s: ModelStatus) = CategoryModelStatusResponse(
            enabled = enabled,
            state = s.state.name.lowercase(),
            mode = s.mode?.name?.lowercase(),
            trainedAt = s.trainedAt?.toString(),
            corrections = s.corrections,
            correctionsNeeded = s.correctionsNeeded,
            trainingRows = s.summary?.trainingRows,
            heldOut = s.summary?.heldOut,
            heldOutPrecision = s.summary?.heldOutPrecision,
            calibrationError = s.summary?.calibrationError
        )
    }
}

/** The result of "retrain now": what happened, and the rows it changed. */
@Serializable
data class CategoryModelTrainResponse(
    /** trained / cold_start / off */
    val outcome: String,
    /** too_few_corrections / one_category, for a cold start */
    val reason: String? = null,
    val applied: Int,
    val suggested: Int,
    val status: CategoryModelStatusResponse
)

/** Retraining is CPU work: a few per hour per user is plenty for a "retrain now" button. */
private val retrainLimiter = RateLimiter(maxRequests = 6, windowSeconds = 3600)

/**
 * `GET /categories/model`: the user's model status. `POST /categories/model/train`: retrain now (AOS-707);
 * 409 `LEARN_001` when learning is switched off, 429 when asked too often.
 */
fun Route.learnedCategoryRoutes(training: CategoryModelTrainingService, limiter: RateLimiter = retrainLimiter) {
    authenticate("auth-jwt") {
        route("/categories/model") {
            get {
                val status = training.status(call.userPrincipal.userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(CategoryModelStatusResponse.from(training.isEnabled, status), call.requestId))
            }

            post("/train") {
                val userId = call.userPrincipal.userId
                if (!training.isEnabled) throw ApiException(HttpStatusCode.Conflict, LEARNING_OFF)
                val key = "retrain:$userId"
                if (!limiter.isAllowed(key)) {
                    throw ApiException(HttpStatusCode.TooManyRequests,
                        ApiError("RATE_001", "Retrained recently. Try again in ${limiter.retryAfterSeconds(key)} seconds."))
                }
                val report = training.retrain(userId)
                val status = CategoryModelStatusResponse.from(training.isEnabled, training.status(userId))
                call.respond(HttpStatusCode.OK, ApiResponse.success(trainResponse(report, status), call.requestId))
            }
        }
    }
}

private val LEARNING_OFF = ApiError("LEARN_001", "Learning from your corrections is switched off on this server")

internal fun trainResponse(report: RetrainReport, status: CategoryModelStatusResponse): CategoryModelTrainResponse {
    val (outcome, reason) = when (val o = report.outcome) {
        is TrainingOutcome.Trained -> "trained" to null
        is TrainingOutcome.ColdStart -> "cold_start" to o.reason.name.lowercase()
        TrainingOutcome.Disabled -> "off" to null
    }
    return CategoryModelTrainResponse(
        outcome = outcome,
        reason = reason,
        applied = report.backfill[LearnedOutcome.APPLIED] ?: 0,
        suggested = report.backfill[LearnedOutcome.SUGGESTED] ?: 0,
        status = status
    )
}
