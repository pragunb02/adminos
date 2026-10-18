package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.financial.TransactionResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.enumParam
import dev.adminos.api.infrastructure.plugins.limitParam
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.util.UUID

/** The owner's display name in the export is short; anything longer isn't a name. */
private const val MAX_ME_CHARS = 200

@Serializable
data class SplitImportResponse(
    val importId: String,
    val rows: Int,
    val alreadyImported: Int,
    val changed: Int,
    val applied: Int,
    val suggested: Int,
    val unmatched: Int,
    val noEffect: Int,
    val unreadableRows: Int
)

@Serializable
data class SplitEntryResponse(
    val id: String,
    /** expense | payment */
    val kind: String,
    /** adjust_debit | owed_spend | settle_out | settle_in | none */
    val effect: String,
    /** applied | suggested | unmatched | ignored | no_effect */
    val state: String,
    val date: String,
    val description: String,
    /** The other person in a settle-up, as named in the export. Shown only to the owner. */
    val counterparty: String?,
    val category: String?,
    /** Exact decimal strings (D-30). */
    val cost: String,
    val currency: String,
    val ownerNet: String,
    val ownerShare: String,
    val score: Int?,
    val reason: String?,
    val transaction: TransactionResponse?
)

@Serializable
data class SplitBalanceResponse(val currency: String, val net: String)

@Serializable
data class SplitSummaryResponse(
    /** Per currency: positive when friends owe the owner, negative when the owner owes. */
    val balances: List<SplitBalanceResponse>,
    val counts: Map<String, Int>
)

@Serializable
data class SplitImportListItem(val id: String, val source: String, val rows: Int, val createdAt: String)

@Serializable
data class SplitConfirmRequest(val transactionId: String? = null)

/**
 * Splitwise (AOS-911, D-111). Owner-only; every entry and import is looked up within the signed-in user.
 * - `POST /imports/splitwise` (multipart: `file` = the CSV export, `me` = the owner's column in it)
 * - `GET /splitwise/entries?state=&limit=`, `GET /splitwise/summary`, `GET /splitwise/imports`
 * - `POST /splitwise/entries/{id}/confirm|reject|ignore|undo`, `DELETE /splitwise/imports/{id}`
 * The file stays in memory; nothing about it is logged.
 */
fun Route.splitRoutes(service: SplitService) {
    authenticate("auth-jwt") {
        post("/imports/splitwise") {
            val userId = call.userPrincipal.userId
            var file: ByteArray? = null
            var me: String? = null
            call.receiveMultipart(formFieldLimit = SplitwiseCsv.MAX_BYTES + 1L).forEachPart { part ->
                when {
                    part is PartData.FileItem && part.name == "file" -> file = part.provider().readRemaining(SplitwiseCsv.MAX_BYTES + 1L).readByteArray()
                    part is PartData.FormItem && part.name == "me" -> me = part.value.take(MAX_ME_CHARS + 1)
                }
                part.dispose()
            }
            val bytes = file ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("SPLITWISE_NO_FILE", "Attach the Splitwise export as 'file'"))
            val name = me?.takeIf { it.isNotBlank() && it.length <= MAX_ME_CHARS }
                ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("SPLITWISE_ME_REQUIRED", "Choose which column of the export is you ('me')"))
            val result = try {
                service.importCsv(userId, bytes, name)
            } catch (e: SplitImportException) {
                val status = if (e.code == "FILE_TOO_LARGE") HttpStatusCode.PayloadTooLarge else HttpStatusCode.UnprocessableEntity
                throw ApiException(status, ApiError(e.code, e.message))
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(SplitImportResponse(
                result.importId.toString(), result.rows, result.alreadyImported, result.changed, result.applied,
                result.suggested, result.unmatched, result.noEffect, result.unreadableRows
            ), call.requestId))
        }

        route("/splitwise") {
            get("/entries") {
                val state = call.enumParam<SplitState>("state")
                val limit = call.limitParam(default = 100, max = 500)
                val list = service.entries(call.userPrincipal.userId, state, limit).map { it.toResponse() }
                call.respond(HttpStatusCode.OK, ApiResponse.success(list, call.requestId))
            }
            get("/summary") {
                val s = service.summary(call.userPrincipal.userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(SplitSummaryResponse(
                    s.balances.toSortedMap().map { (c, v) -> SplitBalanceResponse(c, v.toMoney()) },
                    SplitState.entries.associate { it.name.lowercase() to (s.counts[it] ?: 0) }
                ), call.requestId))
            }
            get("/imports") {
                val list = service.imports(call.userPrincipal.userId).map {
                    SplitImportListItem(it.id.toString(), it.source.name.lowercase(), it.rowsRead, it.createdAt.toString())
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(list, call.requestId))
            }
            delete("/imports/{id}") { act(call) { user, id -> service.undoImport(user, id) } }
            post("/entries/{id}/confirm") {
                val body = if ((call.request.contentLength() ?: 0L) > 0L) call.receive<SplitConfirmRequest>() else SplitConfirmRequest()
                val txn = body.transactionId?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                        ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid 'transactionId': expected a UUID"))
                }
                act(call) { user, id -> service.confirm(user, id, txn) }
            }
            post("/entries/{id}/reject") { act(call) { user, id -> service.reject(user, id) } }
            post("/entries/{id}/ignore") { act(call) { user, id -> service.ignore(user, id) } }
            post("/entries/{id}/undo") { act(call) { user, id -> service.undo(user, id) } }
        }
    }
}

private suspend fun act(call: ApplicationCall, action: suspend (UUID, UUID) -> SplitAction) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid id"))
    when (val result = action(call.userPrincipal.userId, id)) {
        SplitAction.Done -> call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("id" to id.toString()), call.requestId))
        SplitAction.NotFound -> throw ApiException(HttpStatusCode.NotFound, ApiError("SPLIT_001", "Not found"))
        is SplitAction.Refused -> throw ApiException(HttpStatusCode.Conflict, ApiError("SPLIT_002", result.reason))
    }
}

private fun SplitEntryView.toResponse() = SplitEntryResponse(
    id = entry.id.toString(), kind = entry.kind.name.lowercase(), effect = entry.effect.name.lowercase(),
    state = entry.state.name.lowercase(), date = entry.date.toString(), description = entry.description,
    counterparty = entry.counterparty, category = entry.category, cost = entry.cost.toMoney(), currency = entry.currency,
    ownerNet = entry.ownerNet.toMoney(), ownerShare = entry.ownerShare.toMoney(), score = entry.score, reason = entry.reason,
    transaction = transaction?.let(TransactionResponse::from)
)
