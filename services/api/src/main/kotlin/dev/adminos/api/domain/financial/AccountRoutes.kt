package dev.adminos.api.domain.financial

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.accountRoutes(accountDiscoveryService: AccountDiscoveryService) {
    authenticate("auth-jwt") {
        route("/accounts") {
            // GET /api/v1/accounts — list discovered accounts
            get {
                val principal = call.userPrincipal
                val accounts = accountDiscoveryService.getUserAccounts(principal.userId)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        accounts.map { AccountResponse.from(it) },
                        call.requestId
                    )
                )
            }
        }
    }
}
