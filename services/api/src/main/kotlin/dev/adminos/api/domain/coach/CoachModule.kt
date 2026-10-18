package dev.adminos.api.domain.coach

import dev.adminos.api.domain.coach.goals.goalRoutes
import dev.adminos.api.domain.coach.savings.SavingsService
import dev.adminos.api.domain.coach.savings.savingsRoutes
import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.leaks.leakRoutes
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.domain.coach.limits.LimitService
import dev.adminos.api.domain.coach.limits.limitRoutes
import dev.adminos.api.domain.coach.settings.CoachSettingsService
import dev.adminos.api.domain.coach.settings.coachSettingsRoutes
import dev.adminos.api.domain.coach.lenses.lensRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/** The spending coach's routes under /api/v1/coach (Epic E9, D-80). Wiring: `di/CoachKoinModule.kt`. */
fun Application.coachModule() {
    val lenses: LensService by inject()
    val leaks: LeakService by inject()
    val limits: LimitService by inject()
    val settings: CoachSettingsService by inject()
    val goals: dev.adminos.api.domain.coach.goals.GoalService by inject()
    val savings: SavingsService by inject()
    routing {
        route("/api/v1") {
            lensRoutes(lenses)
            leakRoutes(leaks)
            limitRoutes(limits)
            coachSettingsRoutes(settings)
            goalRoutes(goals)
            savingsRoutes(savings)
        }
    }
}
