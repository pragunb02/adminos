package dev.adminos.api.domain.ai

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.SameOriginMutations
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class AiEvaluationResponse(
    /** `recorded-synthetic`, `recorded-real` or `live`: whether the score came from a real model. */
    val answers: String,
    val evaluatedOn: String,
    val goldenRows: Int
)

@Serializable
data class AiFeatureResponse(
    val key: String,
    val title: String,
    val purpose: String,
    val sends: List<String>,
    val neverSends: List<String>,
    /** The user's choice (off until turned on). */
    val enabled: Boolean,
    /** Whether the server runs this feature; when false it can't be turned on. */
    val available: Boolean,
    val provider: String?,
    /** `local` or `cloud`. */
    val locality: String?,
    val model: String?,
    /** Where the data goes, in words. */
    val destination: String?,
    val evaluation: AiEvaluationResponse?,
    /** This user's spend on the feature this month, US dollars (exact string, D-30). */
    val spentThisMonthUsd: String
)

@Serializable
data class AiFeaturesResponse(val features: List<AiFeatureResponse>, val monthlyCapUsd: String)

@Serializable
data class AiConsentRequest(val enabled: Boolean)

private fun AiEvalAnswers.wire() = when (this) {
    AiEvalAnswers.RECORDED_SYNTHETIC -> "recorded-synthetic"
    AiEvalAnswers.RECORDED_REAL -> "recorded-real"
    AiEvalAnswers.LIVE -> "live"
}

internal fun AiFeatureStatus.toResponse() = AiFeatureResponse(
    key = feature.key, title = notice.title, purpose = notice.purpose, sends = notice.sends, neverSends = notice.neverSends,
    enabled = enabled, available = available,
    provider = serving?.provider?.key, locality = serving?.provider?.locality?.name?.lowercase(), model = serving?.model,
    destination = serving?.destination,
    evaluation = evaluation?.let { AiEvaluationResponse(it.answers.wire(), it.evaluatedOn, it.goldenRows) },
    spentThisMonthUsd = Micros.toUsd(spentThisMonthMicros)
)

/**
 * Settings → AI features (AOS-704), signed-in users only, scoped to the caller:
 * - `GET /ai/features`: every feature with its notice, whether the server runs it, spend and the user's choice;
 * - `PATCH /ai/features/{key}` `{"enabled": true|false}`: the user's consent. 404 for an unknown feature, 409
 *   when turning on a feature the server doesn't run, 403 for a cross-site request.
 */
fun Route.aiRoutes(service: AiFeaturesService, appUrl: String) {
    authenticate("auth-jwt") {
        route("/ai/features") {
            get {
                val features = service.list(call.userPrincipal.userId)
                val cap = Micros.toUsd(features.firstOrNull()?.monthlyCapMicros ?: 0)
                call.respond(HttpStatusCode.OK, ApiResponse.success(AiFeaturesResponse(features.map { it.toResponse() }, cap), call.requestId))
            }
            route("/{key}") {
                install(SameOriginMutations) { this.appUrl = appUrl }
                patch {
                    val key = call.parameters["key"].orEmpty()
                    val feature = AiFeature.fromKey(key) ?: throw ApiException(HttpStatusCode.NotFound, ApiError(UNKNOWN_FEATURE, "No such AI feature"))
                    val request = call.receive<AiConsentRequest>()
                    val status = try {
                        service.setEnabled(call.userPrincipal.userId, feature, request.enabled)
                    } catch (e: AiFeatureError.NotAvailable) {
                        throw ApiException(HttpStatusCode.Conflict, ApiError(NOT_AVAILABLE, e.message.orEmpty()))
                    }
                    call.respond(HttpStatusCode.OK, ApiResponse.success(status.toResponse(), call.requestId))
                }
            }
        }
    }
}

const val UNKNOWN_FEATURE = "AI_001"
const val NOT_AVAILABLE = "AI_002"

/** Registers the AI routes (AOS-704). */
fun Application.aiModule() {
    val service: AiFeaturesService by inject()
    val config: AppConfig by inject()
    routing { route("/api/v1") { aiRoutes(service, config.app.appUrl) } }
}
