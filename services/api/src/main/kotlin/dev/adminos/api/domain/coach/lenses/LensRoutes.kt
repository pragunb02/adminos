package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class LateNightDto(val from: String, val to: String)
@Serializable data class LensDto(
    val id: String, val label: String, val description: String,
    val brands: List<String>, val categories: List<String>, val subcategories: List<String>, val keywords: List<String>,
    /** Merchants the owner added to / removed from this lens. */
    val added: List<String>, val removed: List<String>
)
@Serializable data class LensesResponse(val version: String, val lateNight: LateNightDto, val lenses: List<LensDto>)
@Serializable data class LensOverrideRequest(val merchant: String, val include: Boolean)
@Serializable data class LensOverrideResponse(val lens: String, val merchant: String, val include: Boolean)
@Serializable data class LensOverrideClearedResponse(val lens: String, val merchant: String, val cleared: Boolean)

val LENS_NOT_FOUND = ApiError("COACH_001", "Lens not found")

internal fun LensView.toDto(): LensDto {
    fun <T> rule(pick: (LensRule) -> Collection<T>?): List<T> = lens.rules.flatMap { pick(it).orEmpty() }
    return LensDto(
        id = lens.id.value, label = lens.label, description = lens.description,
        brands = rule { (it as? LensRule.Brands)?.brands }.sorted(),
        categories = rule { (it as? LensRule.Categories)?.categories }.map { it.name.lowercase() }.sorted(),
        subcategories = rule { (it as? LensRule.Subcategories)?.subcategories }.sorted(),
        keywords = rule { (it as? LensRule.Keywords)?.keywords }.sorted(),
        added = added.map { it.merchant }, removed = removed.map { it.merchant }
    )
}

/**
 * Habit lenses (AOS-901):
 * - `GET /coach/lenses`: the lenses, their rules and the owner's overrides;
 * - `PUT /coach/lenses/{lens}/merchants` `{merchant, include}`: add or remove a merchant;
 * - `DELETE /coach/lenses/{lens}/merchants?merchant=`: back to the lens's own rules.
 */
fun Route.lensRoutes(service: LensService) {
    authenticate("auth-jwt") {
        route("/coach/lenses") {
            get {
                val views = service.list(call.userPrincipal.userId)
                val window = service.matcher.catalogue.lateNight
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    LensesResponse(service.matcher.catalogue.version, LateNightDto(window.from.toString(), window.to.toString()), views.map { it.toDto() }),
                    call.requestId
                ))
            }
            put("/{lens}/merchants") {
                val lens = call.parameters["lens"].orEmpty()
                val body = call.receive<LensOverrideRequest>()
                val merchant = validMerchant(body.merchant)
                when (val out = service.setOverride(call.userPrincipal.userId, lens, merchant, body.include)) {
                    is OverrideOutcome.Saved -> call.respond(HttpStatusCode.OK, ApiResponse.success(
                        LensOverrideResponse(out.override.lensId.value, out.override.merchant, out.override.include), call.requestId))
                    else -> throw ApiException(HttpStatusCode.NotFound, LENS_NOT_FOUND)
                }
            }
            delete("/{lens}/merchants") {
                val lens = call.parameters["lens"].orEmpty()
                val merchant = validMerchant(call.parameters["merchant"].orEmpty())
                when (service.clearOverride(call.userPrincipal.userId, lens, merchant)) {
                    OverrideOutcome.Cleared -> call.respond(HttpStatusCode.OK, ApiResponse.success(LensOverrideClearedResponse(lens, merchant, true), call.requestId))
                    OverrideOutcome.NothingToClear -> call.respond(HttpStatusCode.OK, ApiResponse.success(LensOverrideClearedResponse(lens, merchant, false), call.requestId))
                    else -> throw ApiException(HttpStatusCode.NotFound, LENS_NOT_FOUND)
                }
            }
        }
    }
}

private fun validMerchant(raw: String): String {
    val merchant = raw.trim()
    require(merchant.isNotEmpty()) { "merchant: required" }
    require(merchant.length <= LensOverride.MAX_MERCHANT) { "merchant: at most ${LensOverride.MAX_MERCHANT} characters" }
    return merchant
}
