package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.Pagination
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.briefingRoutes(
    briefingRepository: BriefingRepository,
    insightRepository: InsightRepository
) {
    authenticate("auth-jwt") {
        route("/briefings") {

            // GET /api/v1/briefings/latest — most recent briefing with insights
            get("/latest") {
                val principal = call.userPrincipal
                val briefing = briefingRepository.findLatestByUserId(principal.userId)

                if (briefing == null) {
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse.success<BriefingResponse?>(null, call.requestId)
                    )
                    return@get
                }

                val insights = insightRepository.findByBriefingId(briefing.id)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(BriefingResponse.from(briefing, insights), call.requestId)
                )
            }

            // GET /api/v1/briefings — cursor-paginated briefing history
            get {
                val principal = call.userPrincipal
                val cursor = call.parameters["cursor"]
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

                val briefings = briefingRepository.findByUserId(
                    userId = principal.userId,
                    cursor = cursor,
                    limit = limit + 1
                )

                val hasMore = briefings.size > limit
                val page = if (hasMore) briefings.take(limit) else briefings
                val nextCursor = if (hasMore) page.lastOrNull()?.createdAt?.toString() else null
                val total = briefingRepository.countByUserId(principal.userId)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.paginated(
                        data = page.map { BriefingResponse.from(it) },
                        pagination = Pagination(cursor = nextCursor, hasMore = hasMore, total = total),
                        requestId = call.requestId
                    )
                )
            }
        }
    }
}
