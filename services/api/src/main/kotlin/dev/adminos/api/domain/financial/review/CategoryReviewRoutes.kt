package dev.adminos.api.domain.financial.review

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.invalidParam
import dev.adminos.api.infrastructure.plugins.limitParam
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

/** A model's guess to confirm or correct. Confidence is 0–1; there are no amounts here beyond the transaction's own. */
@Serializable
data class CategoryGuessResponse(
    val category: String,
    val confidence: Double,
    /** learned / ai */
    val by: String,
    /** true: the guess set the category; false: only a suggestion, the row is still Other. */
    val applied: Boolean,
    val cues: List<String>,
    val reason: String? = null,
    val alternatives: List<String>
)

@Serializable
data class CategoryQuestionResponse(
    val transaction: TransactionResponse,
    val guess: CategoryGuessResponse,
    /** uncertainty × amount: the order of the queue. */
    val priority: Double
) {
    companion object {
        fun from(q: CategoryQuestion) = CategoryQuestionResponse(
            transaction = TransactionResponse.from(q.transaction),
            guess = CategoryGuessResponse(
                category = q.guess.category.name.lowercase(),
                confidence = q.guess.confidence,
                by = q.guess.source.name.lowercase(),
                applied = q.guess.applied,
                cues = q.guess.cues,
                reason = q.guess.reason,
                alternatives = q.guess.alternatives.map { it.name.lowercase() }
            ),
            priority = Math.round(q.priority * 100) / 100.0
        )
    }
}

@Serializable
data class CategoryAnswerRequest(
    val category: String,
    /** Also keep a scrubbed copy in my local golden set. */
    val addToGolden: Boolean = false
)

@Serializable
data class GoldenCandidateResponse(
    val id: String,
    val goldenId: String,
    val merchantRaw: String,
    val paymentMethod: String?,
    val direction: String,
    val category: String,
    val createdAt: String
) {
    companion object {
        fun from(c: GoldenCandidate) = GoldenCandidateResponse(
            id = c.id.toString(), goldenId = c.goldenId, merchantRaw = c.merchantRaw, paymentMethod = c.paymentMethod?.name?.lowercase(),
            direction = c.direction.name.lowercase(), category = c.category.name.lowercase(), createdAt = c.createdAt.toString()
        )
    }
}

@Serializable
data class GoldenResultResponse(
    /** added / already_there / refused */
    val outcome: String,
    /** no_name / looks_like_a_person / not_spending, when refused */
    val reason: String? = null,
    val row: GoldenCandidateResponse? = null
)

@Serializable
data class CategoryAnswerResponse(
    val transaction: TransactionResponse,
    val remembered: Boolean,
    val relabelled: Int,
    val golden: GoldenResultResponse? = null
)

/**
 * The Review page's category questions (AOS-708):
 * - `GET /categories/review?limit=` — open questions, highest uncertainty × amount first;
 * - `POST /categories/review/{transactionId}` `{category, addToGolden}` — confirm or correct (a remembered correction, D-41);
 * - `GET /categories/golden` and `GET /categories/golden.csv` — the local golden set, as JSON or as `golden.csv`-shaped CSV;
 * - `DELETE /categories/golden/{id}` — remove one row from it.
 */
fun Route.categoryReviewRoutes(review: CategoryReviewService) {
    authenticate("auth-jwt") {
        route("/categories") {
            get("/review") {
                val questions = review.questions(call.userPrincipal.userId, call.limitParam(default = 20, max = 50))
                call.respond(HttpStatusCode.OK, ApiResponse.success(questions.map { CategoryQuestionResponse.from(it) }, call.requestId))
            }

            post("/review/{id}") {
                val id = call.idParam()
                val request = call.receive<CategoryAnswerRequest>()
                val category = TransactionCategory.entries.firstOrNull { it.name.equals(request.category, ignoreCase = true) }
                    ?: invalidParam("category", request.category, "one of " + TransactionCategory.entries.joinToString { it.name.lowercase() })
                when (val answer = review.answer(call.userPrincipal.userId, id, category, request.addToGolden)) {
                    ReviewAnswer.NotFound -> throw ApiException(HttpStatusCode.NotFound, ApiError.TRANSACTION_NOT_FOUND)
                    is ReviewAnswer.Answered -> call.respond(HttpStatusCode.OK, ApiResponse.success(answerResponse(answer), call.requestId))
                }
            }

            get("/golden") {
                val rows = review.goldenSet(call.userPrincipal.userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(rows.map { GoldenCandidateResponse.from(it) }, call.requestId))
            }

            get("/golden.csv") {
                val rows = review.goldenSet(call.userPrincipal.userId)
                call.response.header(HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "golden.local.csv").toString())
                call.respondText(CategoryReviewService.csv(rows), ContentType.Text.CSV.withParameter("charset", "utf-8"))
            }

            delete("/golden/{id}") {
                if (!review.removeFromGoldenSet(call.userPrincipal.userId, call.idParam())) throw ApiException(HttpStatusCode.NotFound, GOLDEN_NOT_FOUND)
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("deleted" to true), call.requestId))
            }
        }
    }
}

private val GOLDEN_NOT_FOUND = ApiError("REVIEW_001", "That row isn't in your local golden set")

private fun RoutingCall.idParam(): UUID {
    val raw = parameters["id"].orEmpty()
    return runCatching { UUID.fromString(raw) }.getOrElse { invalidParam("id", raw, "a UUID") }
}

private fun answerResponse(a: ReviewAnswer.Answered) = CategoryAnswerResponse(
    transaction = TransactionResponse.from(a.correction.transaction),
    remembered = a.correction.remembered,
    relabelled = a.correction.relabelled,
    golden = a.golden?.let { g ->
        when (g) {
            is GoldenOutcome.Added -> GoldenResultResponse("added", row = GoldenCandidateResponse.from(g.candidate))
            GoldenOutcome.AlreadyThere -> GoldenResultResponse("already_there")
            is GoldenOutcome.Refused -> GoldenResultResponse("refused", reason = g.reason.name.lowercase())
        }
    }
)
