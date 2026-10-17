package dev.adminos.api.domain.wealth

import dev.adminos.api.domain.wealth.imports.CasImportService
import dev.adminos.api.domain.wealth.imports.wealthImportRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/** Wealth routes under /api/v1/wealth (Epic E8, D-79). Wiring: `di/WealthKoinModule.kt`. */
fun Application.wealthModule() {
    val imports: CasImportService by inject()
    routing {
        route("/api/v1") {
            wealthImportRoutes(imports)
        }
    }
}
